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
package org.apache.hadoop.hbase.regionserver.querymatcher;

import java.io.IOException;
import org.apache.hadoop.hbase.ExtendedCell;
import org.apache.hadoop.hbase.regionserver.ScanInfo;
import org.apache.yetus.audience.InterfaceAudience;

/**
 * A compaction query matcher that always return INCLUDE and drops nothing.
 */
@InterfaceAudience.Private
public class IncludeAllCompactionQueryMatcher extends MinorCompactionScanQueryMatcher {

  public IncludeAllCompactionQueryMatcher(ScanInfo scanInfo, DeleteTracker deletes,
    ColumnTracker columns, long readPointToUse, long oldestUnexpiredTS, long now) {
    super(scanInfo, deletes, columns, readPointToUse, oldestUnexpiredTS, now);
  }

  @Override
  public MatchCode match(ExtendedCell cell) throws IOException {
    return MatchCode.INCLUDE;
  }

}
