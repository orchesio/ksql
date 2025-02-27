/*
 * Copyright 2019 Confluent Inc.
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

package io.confluent.ksql.execution.streams;

import io.confluent.ksql.GenericRow;
import io.confluent.ksql.execution.builder.KsqlQueryBuilder;
import io.confluent.ksql.execution.context.QueryContext;
import io.confluent.ksql.execution.plan.Formats;
import io.confluent.ksql.execution.plan.KTableHolder;
import io.confluent.ksql.execution.plan.TableSink;
import io.confluent.ksql.execution.util.SinkSchemaUtil;
import io.confluent.ksql.schema.ksql.LogicalSchema;
import io.confluent.ksql.schema.ksql.PhysicalSchema;
import io.confluent.ksql.serde.KeySerde;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.streams.kstream.Produced;

public final class TableSinkBuilder {
  private TableSinkBuilder() {
  }

  public static <K> void build(
      final KTableHolder<K> table,
      final TableSink<K> tableSink,
      final KsqlQueryBuilder queryBuilder) {
    final QueryContext queryContext = tableSink.getProperties().getQueryContext();
    final LogicalSchema schema = SinkSchemaUtil.sinkSchema(table.getSchema());
    final Formats formats = tableSink.getFormats();
    final PhysicalSchema physicalSchema = PhysicalSchema.from(schema, formats.getOptions());
    final KeySerde<K> keySerde = table.getKeySerdeFactory().buildKeySerde(
        formats.getKeyFormat(),
        physicalSchema,
        queryContext
    );
    final Serde<GenericRow> valueSerde = queryBuilder.buildValueSerde(
        formats.getValueFormat().getFormatInfo(),
        physicalSchema,
        queryContext
    );
    final Set<Integer> rowkeyIndexes =
        SinkSchemaUtil.implicitAndKeyColumnIndexesInValueSchema(table.getSchema());
    final String kafkaTopicName = tableSink.getTopicName();
    table.getTable().toStream()
        .mapValues(row -> {
              if (row == null) {
                return null;
              }
              final List<Object> columns = new ArrayList<>();
              for (int i = 0; i < row.getColumns().size(); i++) {
                if (!rowkeyIndexes.contains(i)) {
                  columns.add(row.getColumns().get(i));
                }
              }
              return new GenericRow(columns);
            }
        ).to(kafkaTopicName, Produced.with(keySerde, valueSerde));
  }
}