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

import org.apache.hadoop.hbase.HBaseClassTestRule;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.testclassification.ClientTests;
import org.apache.hadoop.hbase.testclassification.MediumTests;
import org.junit.ClassRule;
import org.junit.experimental.categories.Category;

@Category({ MediumTests.class, ClientTests.class })
public class TestSnapshotWithAcl extends SnapshotWithAclTestBase {

  @ClassRule
  public static final HBaseClassTestRule CLASS_RULE =
    HBaseClassTestRule.forClass(TestSnapshotWithAcl.class);

  @Override
  protected void snapshot(String snapshotName, TableName tableName) throws Exception {
    TEST_UTIL.getAdmin().snapshot(snapshotName, tableName);
  }

  @Override
  protected void cloneSnapshot(String snapshotName, TableName tableName, boolean restoreAcl)
    throws Exception {
    TEST_UTIL.getAdmin().cloneSnapshot(snapshotName, tableName, restoreAcl);
  }

  @Override
  protected void restoreSnapshot(String snapshotName, boolean takeFailSafeSnapshot,
    boolean restoreAcl) throws Exception {
    TEST_UTIL.getAdmin().restoreSnapshot(snapshotName, takeFailSafeSnapshot, restoreAcl);
  }
}
