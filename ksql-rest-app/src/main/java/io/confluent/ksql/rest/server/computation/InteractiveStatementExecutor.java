/*
 * Copyright 2018 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.ksql.rest.server.computation;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import io.confluent.ksql.engine.KsqlEngine;
import io.confluent.ksql.exception.ExceptionUtil;
import io.confluent.ksql.metastore.MetaStore;
import io.confluent.ksql.name.SourceName;
import io.confluent.ksql.parser.KsqlParser.ParsedStatement;
import io.confluent.ksql.parser.KsqlParser.PreparedStatement;
import io.confluent.ksql.parser.tree.CreateAsSelect;
import io.confluent.ksql.parser.tree.CreateTableAsSelect;
import io.confluent.ksql.parser.tree.ExecutableDdlStatement;
import io.confluent.ksql.parser.tree.InsertInto;
import io.confluent.ksql.parser.tree.RunScript;
import io.confluent.ksql.parser.tree.TerminateQuery;
import io.confluent.ksql.query.QueryId;
import io.confluent.ksql.query.id.HybridQueryIdGenerator;
import io.confluent.ksql.rest.entity.CommandId;
import io.confluent.ksql.rest.entity.CommandId.Action;
import io.confluent.ksql.rest.entity.CommandId.Type;
import io.confluent.ksql.rest.entity.CommandStatus;
import io.confluent.ksql.rest.server.StatementParser;
import io.confluent.ksql.rest.server.resources.KsqlConfigurable;
import io.confluent.ksql.rest.util.QueryCapacityUtil;
import io.confluent.ksql.services.ServiceContext;
import io.confluent.ksql.statement.ConfiguredStatement;
import io.confluent.ksql.util.KsqlConfig;
import io.confluent.ksql.util.KsqlConstants;
import io.confluent.ksql.util.KsqlException;
import io.confluent.ksql.util.PersistentQueryMetadata;
import io.confluent.ksql.util.QueryMetadata;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.kafka.streams.StreamsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles the actual execution (or delegation to KSQL core) of all distributed statements, as well
 * as tracking their statuses as things move along.
 */
public class InteractiveStatementExecutor implements KsqlConfigurable {

  private static final Logger log = LoggerFactory.getLogger(InteractiveStatementExecutor.class);

  private final ServiceContext serviceContext;
  private final KsqlEngine ksqlEngine;
  private final StatementParser statementParser;
  private final HybridQueryIdGenerator queryIdGenerator;
  private final Map<CommandId, CommandStatus> statusStore;
  private KsqlConfig ksqlConfig;

  private enum Mode {
    RESTORE,
    EXECUTE
  }

  public InteractiveStatementExecutor(
      final ServiceContext serviceContext,
      final KsqlEngine ksqlEngine,
      final HybridQueryIdGenerator hybridQueryIdGenerator
  ) {
    this(
        serviceContext,
        ksqlEngine,
        new StatementParser(ksqlEngine),
        hybridQueryIdGenerator
    );
  }

  @VisibleForTesting
  InteractiveStatementExecutor(
      final ServiceContext serviceContext,
      final KsqlEngine ksqlEngine,
      final StatementParser statementParser,
      final HybridQueryIdGenerator hybridQueryIdGenerator
  ) {
    this.serviceContext = Objects.requireNonNull(serviceContext, "serviceContext");
    this.ksqlEngine = Objects.requireNonNull(ksqlEngine, "ksqlEngine");
    this.statementParser = Objects.requireNonNull(statementParser, "statementParser");
    this.queryIdGenerator =
        Objects.requireNonNull(hybridQueryIdGenerator, "hybridQueryIdGenerator");
    this.statusStore = new ConcurrentHashMap<>();
  }

  @Override
  public void configure(final KsqlConfig config) {
    if (!config.getKsqlStreamConfigProps().containsKey(StreamsConfig.APPLICATION_SERVER_CONFIG)) {
      throw new IllegalArgumentException("Need KS application server set");
    }

    ksqlConfig = config;
  }

  KsqlEngine getKsqlEngine() {
    return ksqlEngine;
  }

  /**
   * Attempt to execute a single statement.
   *
   * @param queuedCommand The command to be executed
   */
  void handleStatement(final QueuedCommand queuedCommand) {
    throwIfNotConfigured();

    handleStatementWithTerminatedQueries(
        queuedCommand.getCommand(),
        queuedCommand.getCommandId(),
        queuedCommand.getStatus(),
        Mode.EXECUTE,
        queuedCommand.getOffset()
    );
  }

  void handleRestore(final QueuedCommand queuedCommand) {
    throwIfNotConfigured();

    handleStatementWithTerminatedQueries(
        queuedCommand.getCommand(),
        queuedCommand.getCommandId(),
        queuedCommand.getStatus(),
        Mode.RESTORE,
        queuedCommand.getOffset()
    );
  }

  /**
   * Get details on the statuses of all the statements handled thus far.
   *
   * @return A map detailing the current statuses of all statements that the handler has executed
   *     (or attempted to execute).
   */
  public Map<CommandId, CommandStatus> getStatuses() {
    return new HashMap<>(statusStore);
  }

  /**
   * @param statementId The ID of the statement to check the status of.
   * @return Information on the status of the statement with the given ID, if one exists.
   */
  public Optional<CommandStatus> getStatus(final CommandId statementId) {
    return Optional.ofNullable(statusStore.get(statementId));
  }

  private void throwIfNotConfigured() {
    if (ksqlConfig == null) {
      throw new IllegalStateException("No initialized");
    }
  }

  private void putStatus(final CommandId commandId,
                        final Optional<CommandStatusFuture> commandStatusFuture,
                        final CommandStatus status) {
    statusStore.put(commandId, status);
    commandStatusFuture.ifPresent(s -> s.setStatus(status));
  }

  private void putFinalStatus(final CommandId commandId,
                             final Optional<CommandStatusFuture> commandStatusFuture,
                             final CommandStatus status) {
    statusStore.put(commandId, status);
    commandStatusFuture.ifPresent(s -> s.setFinalStatus(status));
  }

  /**
   * Attempt to execute a single statement.
   *
   * @param command The string containing the statement to be executed
   * @param commandId The ID to be used to track the status of the command
   * @param mode was this table/stream subsequently dropped
   */
  private void handleStatementWithTerminatedQueries(
      final Command command,
      final CommandId commandId,
      final Optional<CommandStatusFuture> commandStatusFuture,
      final Mode mode,
      final long offset
  ) {
    try {
      final String statementString = command.getStatement();
      maybeTerminateQueryForLegacyDropCommand(commandId, command);
      putStatus(
          commandId,
          commandStatusFuture,
          new CommandStatus(CommandStatus.Status.PARSING, "Parsing statement"));
      final PreparedStatement<?> statement = statementParser.parseSingleStatement(statementString);
      putStatus(
          commandId,
          commandStatusFuture,
          new CommandStatus(CommandStatus.Status.EXECUTING, "Executing statement")
      );
      executeStatement(
          statement, command, commandId, commandStatusFuture, mode, offset);
    } catch (final KsqlException exception) {
      log.error("Failed to handle: " + command, exception);
      final CommandStatus errorStatus = new CommandStatus(
          CommandStatus.Status.ERROR,
          ExceptionUtil.stackTraceToString(exception)
      );
      putFinalStatus(commandId, commandStatusFuture, errorStatus);
    }
  }

  @SuppressWarnings({"unchecked", "deprecation"})
  private void executeStatement(
      final PreparedStatement<?> statement,
      final Command command,
      final CommandId commandId,
      final Optional<CommandStatusFuture> commandStatusFuture,
      final Mode mode,
      final long offset
  ) {
    String successMessage = "";
    if (statement.getStatement() instanceof ExecutableDdlStatement) {
      successMessage = executeDdlStatement(statement, command);
    } else if (statement.getStatement() instanceof CreateAsSelect) {
      final PersistentQueryMetadata query = startQuery(statement, command, mode, offset);
      final String name = ((CreateAsSelect)statement.getStatement()).getName().name();
      successMessage = statement.getStatement() instanceof CreateTableAsSelect
          ? "Table " + name + " created and running" : "Stream " + name + " created and running";
      successMessage += ". Created by query with query ID: " + query.getQueryId();
    } else if (statement.getStatement() instanceof InsertInto) {
      final PersistentQueryMetadata query = startQuery(statement, command, mode, offset);
      successMessage = "Insert Into query is running with query ID: " + query.getQueryId();
    } else if (statement.getStatement() instanceof TerminateQuery) {
      terminateQuery((PreparedStatement<TerminateQuery>) statement);
      successMessage = "Query terminated.";
    } else if (statement.getStatement() instanceof RunScript) {
      handleLegacyRunScript(command, mode);
    } else {
      throw new KsqlException(String.format(
          "Unexpected statement type: %s",
          statement.getClass().getName()
      ));
    }

    final CommandStatus successStatus =
        new CommandStatus(CommandStatus.Status.SUCCESS, successMessage);

    putFinalStatus(commandId, commandStatusFuture, successStatus);
  }

  private String executeDdlStatement(final PreparedStatement<?> statement, final Command command) {
    final KsqlConfig mergedConfig = buildMergedConfig(command);
    final ConfiguredStatement<?> configured =
        ConfiguredStatement.of(statement, command.getOverwriteProperties(), mergedConfig);

    return ksqlEngine
        .execute(serviceContext, configured)
        .getCommandResult()
        .get();
  }

  /**
   * @deprecated deprecate since 5.2. `RUN SCRIPT` will be removed from syntax in later release.
   */
  @SuppressWarnings("DeprecatedIsStillUsed")
  @Deprecated
  private void handleLegacyRunScript(final Command command, final Mode mode) {

    final String sql = (String) command.getOverwriteProperties()
        .get(KsqlConstants.LEGACY_RUN_SCRIPT_STATEMENTS_CONTENT);

    if (sql == null) {
      throw new KsqlException("No statements received for LOAD FROM FILE.");
    }

    final Map<String, Object> overriddenProperties = new HashMap<>(
        command.getOverwriteProperties());

    final KsqlConfig mergedConfig = buildMergedConfig(command);

    final List<QueryMetadata> queries = new ArrayList<>();
    for (final ParsedStatement parsed : ksqlEngine.parse(sql)) {
      final PreparedStatement<?> prepared = ksqlEngine.prepare(parsed);
      final ConfiguredStatement<?> configured =
          ConfiguredStatement.of(prepared, overriddenProperties, ksqlConfig);
      ksqlEngine.execute(serviceContext, configured)
          .getQuery()
          .ifPresent(queries::add);
    }

    if (QueryCapacityUtil.exceedsPersistentQueryCapacity(ksqlEngine, mergedConfig, 0)) {
      queries.forEach(QueryMetadata::close);
      QueryCapacityUtil.throwTooManyActivePersistentQueriesException(
          ksqlEngine, mergedConfig, command.getStatement());
    }

    if (mode == Mode.EXECUTE) {
      for (final QueryMetadata queryMetadata : queries) {
        if (queryMetadata instanceof PersistentQueryMetadata) {
          final PersistentQueryMetadata persistentQueryMd =
              (PersistentQueryMetadata) queryMetadata;
          persistentQueryMd.start();
        }
      }
    }
  }

  private PersistentQueryMetadata startQuery(
      final PreparedStatement<?> statement,
      final Command command,
      final Mode mode,
      final long offset
  ) {
    final KsqlConfig mergedConfig = buildMergedConfig(command);

    if (QueryCapacityUtil.exceedsPersistentQueryCapacity(ksqlEngine, mergedConfig,1)) {
      QueryCapacityUtil.throwTooManyActivePersistentQueriesException(
          ksqlEngine, mergedConfig, statement.getStatementText());
    }

    final ConfiguredStatement<?> configured = ConfiguredStatement.of(
        statement, command.getOverwriteProperties(), mergedConfig);

    if (command.getUseOffsetAsQueryID()) {
      queryIdGenerator.activateNewGenerator(offset);
    }

    final QueryMetadata queryMetadata = ksqlEngine.execute(serviceContext, configured)
        .getQuery()
        .orElseThrow(() -> new IllegalStateException("Statement did not return a query"));

    if (!(queryMetadata instanceof PersistentQueryMetadata)) {
      throw new KsqlException(String.format(
          "Unexpected query metadata type: %s; was expecting %s",
          queryMetadata.getClass().getCanonicalName(),
          PersistentQueryMetadata.class.getCanonicalName()
      ));
    }

    final PersistentQueryMetadata persistentQueryMd = (PersistentQueryMetadata) queryMetadata;
    if (mode == Mode.EXECUTE) {
      persistentQueryMd.start();
    }
    return persistentQueryMd;
  }

  private KsqlConfig buildMergedConfig(final Command command) {
    return ksqlConfig.overrideBreakingConfigsWithOriginalValues(command.getOriginalProperties());
  }

  private void terminateQuery(final PreparedStatement<TerminateQuery> terminateQuery) {
    final QueryId queryId = terminateQuery.getStatement().getQueryId();

    ksqlEngine.getPersistentQuery(queryId)
        .orElseThrow(() ->
            new KsqlException(String.format("No running query with id %s was found", queryId)))
        .close();
  }

  private void maybeTerminateQueryForLegacyDropCommand(
      final CommandId commandId,
      final Command command) {
    if (!command.isPreVersion5()
        || !commandId.getAction().equals(Action.DROP)
        || !(commandId.getType().equals(Type.STREAM) || commandId.getType().equals(Type.TABLE))) {
      return;
    }
    final MetaStore metaStore = ksqlEngine.getMetaStore();
    if (metaStore.getSource(SourceName.of(commandId.getEntity())) == null) {
      return;
    }
    final Collection<String> queriesWithSink
        = Lists.newArrayList(metaStore.getQueriesWithSink(SourceName.of(commandId.getEntity())));
    queriesWithSink.stream()
        .map(QueryId::new)
        .map(ksqlEngine::getPersistentQuery)
        .filter(Optional::isPresent)
        .map(Optional::get)
        .forEach(QueryMetadata::close);
  }
}
