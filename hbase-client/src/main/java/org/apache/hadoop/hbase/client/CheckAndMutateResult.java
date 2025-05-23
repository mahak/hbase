/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.client;

import org.apache.yetus.audience.InterfaceAudience;

/**
 * Represents a result of a CheckAndMutate operation
 */
@InterfaceAudience.Public
public class CheckAndMutateResult {
  private final boolean success;
  private final Result result;

  private QueryMetrics metrics = null;

  public CheckAndMutateResult(boolean success, Result result) {
    this.success = success;
    this.result = result;
  }

  /** Returns Whether the CheckAndMutate operation is successful or not */
  public boolean isSuccess() {
    return success;
  }

  /** Returns It is used only for CheckAndMutate operations with Increment/Append. Otherwise null */
  public Result getResult() {
    return result;
  }

  public CheckAndMutateResult setMetrics(QueryMetrics metrics) {
    this.metrics = metrics;
    return this;
  }

  public QueryMetrics getMetrics() {
    return metrics;
  }
}
