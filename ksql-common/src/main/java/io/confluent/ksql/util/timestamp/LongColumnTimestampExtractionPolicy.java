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

package io.confluent.ksql.util.timestamp;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.errorprone.annotations.Immutable;
import io.confluent.ksql.schema.ksql.ColumnRef;
import java.util.Objects;
import org.apache.kafka.streams.processor.TimestampExtractor;

@Immutable
public class LongColumnTimestampExtractionPolicy implements TimestampExtractionPolicy {

  private final ColumnRef timestampField;

  public LongColumnTimestampExtractionPolicy(
      @JsonProperty(value = "timestampField", required = true) final ColumnRef timestampField) {
    Objects.requireNonNull(timestampField, "timestampField can't be null");
    this.timestampField = timestampField;
  }

  @Override
  public TimestampExtractor create(final int columnIndex) {
    return new LongTimestampExtractor(columnIndex);
  }

  @Override
  public ColumnRef getTimestampField() {
    return timestampField;
  }

  @Override
  public int hashCode() {
    return Objects.hash(timestampField);
  }

  @Override
  public boolean equals(final Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof LongColumnTimestampExtractionPolicy)) {
      return false;
    }
    final LongColumnTimestampExtractionPolicy otherPolicy
        = (LongColumnTimestampExtractionPolicy) other;
    return Objects.equals(otherPolicy.timestampField, timestampField);
  }
}
