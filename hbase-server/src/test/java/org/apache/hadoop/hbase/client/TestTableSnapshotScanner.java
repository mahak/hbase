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

import static org.apache.hadoop.hbase.client.metrics.ScanMetrics.REGIONS_SCANNED_METRIC_NAME;
import static org.apache.hadoop.hbase.client.metrics.ServerSideScanMetrics.COUNT_OF_ROWS_SCANNED_KEY_METRIC_NAME;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellScanner;
import org.apache.hadoop.hbase.HBaseClassTestRule;
import org.apache.hadoop.hbase.HBaseTestingUtil;
import org.apache.hadoop.hbase.StartTestingClusterOption;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.metrics.ScanMetrics;
import org.apache.hadoop.hbase.client.metrics.ScanMetricsRegionInfo;
import org.apache.hadoop.hbase.master.cleaner.TimeToLiveHFileCleaner;
import org.apache.hadoop.hbase.master.snapshot.SnapshotManager;
import org.apache.hadoop.hbase.regionserver.HRegion;
import org.apache.hadoop.hbase.regionserver.HRegionFileSystem;
import org.apache.hadoop.hbase.regionserver.HRegionServer;
import org.apache.hadoop.hbase.regionserver.StoreContext;
import org.apache.hadoop.hbase.regionserver.storefiletracker.StoreFileTracker;
import org.apache.hadoop.hbase.regionserver.storefiletracker.StoreFileTrackerFactory;
import org.apache.hadoop.hbase.snapshot.RestoreSnapshotHelper;
import org.apache.hadoop.hbase.snapshot.SnapshotTestingUtils;
import org.apache.hadoop.hbase.testclassification.ClientTests;
import org.apache.hadoop.hbase.testclassification.LargeTests;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.CommonFSUtils;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;
import org.apache.hadoop.hbase.util.FSUtils;
import org.apache.hadoop.hbase.util.HFileArchiveUtil;
import org.apache.hadoop.hbase.util.JVMClusterUtil.RegionServerThread;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.TestName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Category({ LargeTests.class, ClientTests.class })
public class TestTableSnapshotScanner {

  @ClassRule
  public static final HBaseClassTestRule CLASS_RULE =
    HBaseClassTestRule.forClass(TestTableSnapshotScanner.class);

  private static final Logger LOG = LoggerFactory.getLogger(TestTableSnapshotScanner.class);
  private final HBaseTestingUtil UTIL = new HBaseTestingUtil();
  private static final int NUM_REGION_SERVERS = 2;
  private static final byte[][] FAMILIES = { Bytes.toBytes("f1"), Bytes.toBytes("f2") };
  public static byte[] bbb = Bytes.toBytes("bbb");
  public static byte[] yyy = Bytes.toBytes("yyy");

  private FileSystem fs;
  private Path rootDir;
  private boolean clusterUp;

  @Rule
  public TestName name = new TestName();

  public static void blockUntilSplitFinished(HBaseTestingUtil util, TableName tableName,
    int expectedRegionSize) throws Exception {
    for (int i = 0; i < 100; i++) {
      List<RegionInfo> hRegionInfoList = util.getAdmin().getRegions(tableName);
      if (hRegionInfoList.size() >= expectedRegionSize) {
        break;
      }
      Thread.sleep(1000);
    }
  }

  @Before
  public void setupCluster() throws Exception {
    setupConf(UTIL.getConfiguration());
    StartTestingClusterOption option =
      StartTestingClusterOption.builder().numRegionServers(NUM_REGION_SERVERS)
        .numDataNodes(NUM_REGION_SERVERS).createRootDir(true).build();
    UTIL.startMiniCluster(option);
    clusterUp = true;
    rootDir = UTIL.getHBaseCluster().getMaster().getMasterFileSystem().getRootDir();
    fs = rootDir.getFileSystem(UTIL.getConfiguration());
  }

  @After
  public void tearDownCluster() throws Exception {
    if (clusterUp) {
      UTIL.shutdownMiniCluster();
    }
  }

  protected void setupConf(Configuration conf) {
    // Enable snapshot
    conf.setBoolean(SnapshotManager.HBASE_SNAPSHOT_ENABLED, true);
  }

  public static void createTableAndSnapshot(HBaseTestingUtil util, TableName tableName,
    String snapshotName, int numRegions) throws Exception {
    try {
      util.deleteTable(tableName);
    } catch (Exception ex) {
      // ignore
    }

    if (numRegions > 1) {
      util.createTable(tableName, FAMILIES, 1, bbb, yyy, numRegions);
    } else {
      util.createTable(tableName, FAMILIES);
    }
    Admin admin = util.getAdmin();

    // put some stuff in the table
    Table table = util.getConnection().getTable(tableName);
    util.loadTable(table, FAMILIES);

    Path rootDir = CommonFSUtils.getRootDir(util.getConfiguration());
    FileSystem fs = rootDir.getFileSystem(util.getConfiguration());

    SnapshotTestingUtils.createSnapshotAndValidate(admin, tableName, Arrays.asList(FAMILIES), null,
      snapshotName, rootDir, fs, true);

    // load different values
    byte[] value = Bytes.toBytes("after_snapshot_value");
    util.loadTable(table, FAMILIES, value);

    // cause flush to create new files in the region
    admin.flush(tableName);
    table.close();
  }

  @Test
  public void testNoDuplicateResultsWhenSplitting() throws Exception {
    TableName tableName = TableName.valueOf("testNoDuplicateResultsWhenSplitting");
    String snapshotName = "testSnapshotBug";
    try {
      if (UTIL.getAdmin().tableExists(tableName)) {
        UTIL.deleteTable(tableName);
      }

      UTIL.createTable(tableName, FAMILIES);
      Admin admin = UTIL.getAdmin();

      // put some stuff in the table
      Table table = UTIL.getConnection().getTable(tableName);
      UTIL.loadTable(table, FAMILIES);

      // split to 2 regions
      admin.split(tableName, Bytes.toBytes("eee"));
      blockUntilSplitFinished(UTIL, tableName, 2);

      Path rootDir = CommonFSUtils.getRootDir(UTIL.getConfiguration());
      FileSystem fs = rootDir.getFileSystem(UTIL.getConfiguration());

      SnapshotTestingUtils.createSnapshotAndValidate(admin, tableName, Arrays.asList(FAMILIES),
        null, snapshotName, rootDir, fs, true);

      // load different values
      byte[] value = Bytes.toBytes("after_snapshot_value");
      UTIL.loadTable(table, FAMILIES, value);

      // cause flush to create new files in the region
      admin.flush(tableName);
      table.close();

      Path restoreDir = UTIL.getDataTestDirOnTestFS(snapshotName);
      Scan scan = new Scan().withStartRow(bbb).withStopRow(yyy); // limit the scan

      TableSnapshotScanner scanner =
        new TableSnapshotScanner(UTIL.getConfiguration(), restoreDir, snapshotName, scan);

      verifyScanner(scanner, bbb, yyy);
      scanner.close();
    } catch (Exception e) {
      e.printStackTrace();
    } finally {
      UTIL.getAdmin().deleteSnapshot(snapshotName);
      UTIL.deleteTable(tableName);
    }
  }

  @Test
  public void testScanLimit() throws Exception {
    final TableName tableName = TableName.valueOf(name.getMethodName());
    final String snapshotName = tableName + "Snapshot";
    TableSnapshotScanner scanner = null;
    try {
      createTableAndSnapshot(UTIL, tableName, snapshotName, 50);
      Path restoreDir = UTIL.getDataTestDirOnTestFS(snapshotName);
      Scan scan = new Scan().withStartRow(bbb).setLimit(100); // limit the scan

      scanner = new TableSnapshotScanner(UTIL.getConfiguration(), restoreDir, snapshotName, scan);
      int count = 0;
      while (true) {
        Result result = scanner.next();
        if (result == null) {
          break;
        }
        count++;
      }
      Assert.assertEquals(100, count);
    } finally {
      if (scanner != null) {
        scanner.close();
      }
      UTIL.getAdmin().deleteSnapshot(snapshotName);
      UTIL.deleteTable(tableName);
    }
  }

  @Test
  public void testWithSingleRegion() throws Exception {
    testScanner(UTIL, "testWithSingleRegion", 1, false);
  }

  @Test
  public void testWithMultiRegion() throws Exception {
    testScanner(UTIL, "testWithMultiRegion", 10, false);
  }

  @Test
  public void testWithOfflineHBaseMultiRegion() throws Exception {
    testScanner(UTIL, "testWithMultiRegion", 20, true);
  }

  private ScanMetrics createTableSnapshotScannerAndGetScanMetrics(boolean enableScanMetrics,
    boolean enableScanMetricsByRegion, byte[] endKey) throws Exception {
    TableName tableName = TableName.valueOf(name.getMethodName() + "_TABLE");
    String snapshotName = name.getMethodName() + "_SNAPSHOT";
    try {
      createTableAndSnapshot(UTIL, tableName, snapshotName, 50);
      Path restoreDir = UTIL.getDataTestDirOnTestFS(snapshotName);
      Scan scan = new Scan().withStartRow(bbb).withStopRow(endKey);
      scan.setScanMetricsEnabled(enableScanMetrics);
      scan.setEnableScanMetricsByRegion(enableScanMetricsByRegion);
      Configuration conf = UTIL.getConfiguration();

      TableSnapshotScanner snapshotScanner =
        new TableSnapshotScanner(conf, restoreDir, snapshotName, scan);
      verifyScanner(snapshotScanner, bbb, endKey);
      return snapshotScanner.getScanMetrics();
    } finally {
      UTIL.getAdmin().deleteSnapshot(snapshotName);
      UTIL.deleteTable(tableName);
    }
  }

  @Test
  public void testScanMetricsDisabled() throws Exception {
    ScanMetrics scanMetrics = createTableSnapshotScannerAndGetScanMetrics(false, false, yyy);
    Assert.assertNull(scanMetrics);
  }

  @Test
  public void testScanMetricsWithScanMetricsByRegionDisabled() throws Exception {
    ScanMetrics scanMetrics = createTableSnapshotScannerAndGetScanMetrics(true, false, yyy);
    Assert.assertNotNull(scanMetrics);
    int rowsScanned = 0;
    for (byte[] row : HBaseTestingUtil.ROWS) {
      if (Bytes.compareTo(row, bbb) >= 0 && Bytes.compareTo(row, yyy) < 0) {
        rowsScanned++;
      }
    }
    Map<String, Long> metricsMap = scanMetrics.getMetricsMap();
    Assert.assertEquals(rowsScanned, (long) metricsMap.get(COUNT_OF_ROWS_SCANNED_KEY_METRIC_NAME));
  }

  @Test
  public void testScanMetricsByRegionForSingleRegion() throws Exception {
    // Scan single row with row key bbb
    byte[] bbc = Bytes.toBytes("bbc");
    ScanMetrics scanMetrics = createTableSnapshotScannerAndGetScanMetrics(true, true, bbc);
    Assert.assertNotNull(scanMetrics);
    Map<ScanMetricsRegionInfo, Map<String, Long>> scanMetricsByRegion =
      scanMetrics.collectMetricsByRegion();
    Assert.assertEquals(1, scanMetricsByRegion.size());
    for (Map.Entry<ScanMetricsRegionInfo, Map<String, Long>> entry : scanMetricsByRegion
      .entrySet()) {
      ScanMetricsRegionInfo scanMetricsRegionInfo = entry.getKey();
      Map<String, Long> metricsMap = entry.getValue();
      Assert.assertNull(scanMetricsRegionInfo.getServerName());
      Assert.assertNotNull(scanMetricsRegionInfo.getEncodedRegionName());
      Assert.assertEquals(1, (long) metricsMap.get(REGIONS_SCANNED_METRIC_NAME));
      Assert.assertEquals(1, (long) metricsMap.get(COUNT_OF_ROWS_SCANNED_KEY_METRIC_NAME));
    }
  }

  @Test
  public void testScanMetricsByRegionForMultiRegion() throws Exception {
    ScanMetrics scanMetrics = createTableSnapshotScannerAndGetScanMetrics(true, true, yyy);
    Assert.assertNotNull(scanMetrics);
    Map<ScanMetricsRegionInfo, Map<String, Long>> scanMetricsByRegion =
      scanMetrics.collectMetricsByRegion();
    for (Map.Entry<ScanMetricsRegionInfo, Map<String, Long>> entry : scanMetricsByRegion
      .entrySet()) {
      ScanMetricsRegionInfo scanMetricsRegionInfo = entry.getKey();
      Map<String, Long> metricsMap = entry.getValue();
      Assert.assertNull(scanMetricsRegionInfo.getServerName());
      Assert.assertNotNull(scanMetricsRegionInfo.getEncodedRegionName());
      Assert.assertEquals(1, (long) metricsMap.get(REGIONS_SCANNED_METRIC_NAME));
    }
  }

  @Test
  public void testScannerWithRestoreScanner() throws Exception {
    TableName tableName = TableName.valueOf("testScanner");
    String snapshotName = "testScannerWithRestoreScanner";
    try {
      createTableAndSnapshot(UTIL, tableName, snapshotName, 50);
      Path restoreDir = UTIL.getDataTestDirOnTestFS(snapshotName);
      Scan scan = new Scan().withStartRow(bbb).withStopRow(yyy); // limit the scan

      Configuration conf = UTIL.getConfiguration();
      Path rootDir = CommonFSUtils.getRootDir(conf);

      TableSnapshotScanner scanner0 =
        new TableSnapshotScanner(conf, restoreDir, snapshotName, scan);
      verifyScanner(scanner0, bbb, yyy);
      scanner0.close();

      // restore snapshot.
      RestoreSnapshotHelper.copySnapshotForScanner(conf, fs, rootDir, restoreDir, snapshotName);

      // scan the snapshot without restoring snapshot
      TableSnapshotScanner scanner =
        new TableSnapshotScanner(conf, rootDir, restoreDir, snapshotName, scan, true);
      verifyScanner(scanner, bbb, yyy);
      scanner.close();

      // check whether the snapshot has been deleted by the close of scanner.
      scanner = new TableSnapshotScanner(conf, rootDir, restoreDir, snapshotName, scan, true);
      verifyScanner(scanner, bbb, yyy);
      scanner.close();

      // restore snapshot again.
      RestoreSnapshotHelper.copySnapshotForScanner(conf, fs, rootDir, restoreDir, snapshotName);

      // check whether the snapshot has been deleted by the close of scanner.
      scanner = new TableSnapshotScanner(conf, rootDir, restoreDir, snapshotName, scan, true);
      verifyScanner(scanner, bbb, yyy);
      scanner.close();
    } finally {
      UTIL.getAdmin().deleteSnapshot(snapshotName);
      UTIL.deleteTable(tableName);
    }
  }

  private void testScanner(HBaseTestingUtil util, String snapshotName, int numRegions,
    boolean shutdownCluster) throws Exception {
    TableName tableName = TableName.valueOf("testScanner");
    try {
      createTableAndSnapshot(util, tableName, snapshotName, numRegions);

      if (shutdownCluster) {
        util.shutdownMiniHBaseCluster();
        clusterUp = false;
      }

      Path restoreDir = util.getDataTestDirOnTestFS(snapshotName);
      Scan scan = new Scan().withStartRow(bbb).withStopRow(yyy); // limit the scan

      TableSnapshotScanner scanner =
        new TableSnapshotScanner(UTIL.getConfiguration(), restoreDir, snapshotName, scan);

      verifyScanner(scanner, bbb, yyy);
      scanner.close();
    } finally {
      if (clusterUp) {
        util.getAdmin().deleteSnapshot(snapshotName);
        util.deleteTable(tableName);
      }
    }
  }

  private void verifyScanner(ResultScanner scanner, byte[] startRow, byte[] stopRow)
    throws IOException, InterruptedException {

    HBaseTestingUtil.SeenRowTracker rowTracker =
      new HBaseTestingUtil.SeenRowTracker(startRow, stopRow);

    while (true) {
      Result result = scanner.next();
      if (result == null) {
        break;
      }
      verifyRow(result);
      rowTracker.addRow(result.getRow());
    }

    // validate all rows are seen
    rowTracker.validate();
  }

  private static void verifyRow(Result result) throws IOException {
    byte[] row = result.getRow();
    CellScanner scanner = result.cellScanner();
    while (scanner.advance()) {
      Cell cell = scanner.current();

      // assert that all Cells in the Result have the same key
      Assert.assertEquals(0, Bytes.compareTo(row, 0, row.length, cell.getRowArray(),
        cell.getRowOffset(), cell.getRowLength()));
    }

    for (int j = 0; j < FAMILIES.length; j++) {
      byte[] actual = result.getValue(FAMILIES[j], FAMILIES[j]);
      Assert.assertArrayEquals("Row in snapshot does not match, expected:" + Bytes.toString(row)
        + " ,actual:" + Bytes.toString(actual), row, actual);
    }
  }

  @Test
  public void testMergeRegion() throws Exception {
    TableName tableName = TableName.valueOf("testMergeRegion");
    String snapshotName = tableName.getNameAsString() + "_snapshot";
    Configuration conf = UTIL.getConfiguration();
    Path rootDir = UTIL.getHBaseCluster().getMaster().getMasterFileSystem().getRootDir();
    long timeout = 20000; // 20s
    try (Admin admin = UTIL.getAdmin()) {
      List<String> serverList = admin.getRegionServers().stream().map(sn -> sn.getServerName())
        .collect(Collectors.toList());
      // create table with 3 regions
      Table table = UTIL.createTable(tableName, FAMILIES, 1, bbb, yyy, 3);
      List<RegionInfo> regions = admin.getRegions(tableName);
      Assert.assertEquals(3, regions.size());
      RegionInfo region0 = regions.get(0);
      RegionInfo region1 = regions.get(1);
      RegionInfo region2 = regions.get(2);
      // put some data in the table
      UTIL.loadTable(table, FAMILIES);
      admin.flush(tableName);
      // wait flush is finished
      UTIL.waitFor(timeout, () -> {
        try {
          Path tableDir = CommonFSUtils.getTableDir(rootDir, tableName);
          for (RegionInfo region : regions) {
            Path regionDir = new Path(tableDir, region.getEncodedName());
            for (Path familyDir : FSUtils.getFamilyDirs(fs, regionDir)) {
              for (FileStatus fs : fs.listStatus(familyDir)) {
                if (!fs.getPath().getName().equals(".filelist")) {
                  return true;
                }
              }
              return false;
            }
          }
          return true;
        } catch (IOException e) {
          LOG.warn("Failed check if flush is finished", e);
          return false;
        }
      });
      // merge 2 regions
      admin.compactionSwitch(false, serverList);
      admin.mergeRegionsAsync(region0.getEncodedNameAsBytes(), region1.getEncodedNameAsBytes(),
        true);
      UTIL.waitFor(timeout, () -> admin.getRegions(tableName).size() == 2);
      List<RegionInfo> mergedRegions = admin.getRegions(tableName);
      RegionInfo mergedRegion =
        mergedRegions.get(0).getEncodedName().equals(region2.getEncodedName())
          ? mergedRegions.get(1)
          : mergedRegions.get(0);
      // snapshot
      admin.snapshot(snapshotName, tableName);
      Assert.assertEquals(1, admin.listSnapshots().size());
      // major compact
      admin.compactionSwitch(true, serverList);
      admin.majorCompactRegion(mergedRegion.getRegionName());
      // wait until merged region has no reference
      UTIL.waitFor(timeout, () -> {
        try {
          for (RegionServerThread regionServerThread : UTIL.getMiniHBaseCluster()
            .getRegionServerThreads()) {
            HRegionServer regionServer = regionServerThread.getRegionServer();
            for (HRegion subRegion : regionServer.getRegions(tableName)) {
              if (
                subRegion.getRegionInfo().getEncodedName().equals(mergedRegion.getEncodedName())
              ) {
                regionServer.getCompactedHFilesDischarger().chore();
              }
            }
          }
          Path tableDir = CommonFSUtils.getTableDir(rootDir, tableName);
          HRegionFileSystem regionFs = HRegionFileSystem
            .openRegionFromFileSystem(UTIL.getConfiguration(), fs, tableDir, mergedRegion, true);
          boolean references = false;
          Path regionDir = new Path(tableDir, mergedRegion.getEncodedName());
          for (Path familyDir : FSUtils.getFamilyDirs(fs, regionDir)) {
            StoreContext storeContext = StoreContext.getBuilder()
              .withColumnFamilyDescriptor(ColumnFamilyDescriptorBuilder.of(familyDir.getName()))
              .withRegionFileSystem(regionFs).withFamilyStoreDirectoryPath(familyDir).build();
            StoreFileTracker sft =
              StoreFileTrackerFactory.create(UTIL.getConfiguration(), false, storeContext);
            references = references || sft.hasReferences();
            if (references) {
              break;
            }
          }
          return !references;
        } catch (IOException e) {
          LOG.warn("Failed check merged region has no reference", e);
          return false;
        }
      });
      // run catalog janitor to clean and wait for parent regions are archived
      UTIL.getMiniHBaseCluster().getMaster().getCatalogJanitor().choreForTesting();
      UTIL.waitFor(timeout, () -> {
        try {
          Path tableDir = CommonFSUtils.getTableDir(rootDir, tableName);
          for (FileStatus fileStatus : fs.listStatus(tableDir)) {
            String name = fileStatus.getPath().getName();
            if (name.equals(region0.getEncodedName()) || name.equals(region1.getEncodedName())) {
              return false;
            }
          }
          return true;
        } catch (IOException e) {
          LOG.warn("Check if parent regions are archived error", e);
          return false;
        }
      });
      // set file modify time and then run cleaner
      long time = EnvironmentEdgeManager.currentTime() - TimeToLiveHFileCleaner.DEFAULT_TTL * 1000;
      traverseAndSetFileTime(HFileArchiveUtil.getArchivePath(conf), time);
      UTIL.getMiniHBaseCluster().getMaster().getHFileCleaner().triggerCleanerNow().get();
      // scan snapshot
      try (TableSnapshotScanner scanner =
        new TableSnapshotScanner(conf, UTIL.getDataTestDirOnTestFS(snapshotName), snapshotName,
          new Scan().withStartRow(bbb).withStopRow(yyy))) {
        verifyScanner(scanner, bbb, yyy);
      }
    } catch (Exception e) {
      LOG.error("scan snapshot error", e);
      Assert.fail("Should not throw Exception: " + e.getMessage());
    }
  }

  @Test
  public void testDeleteTableWithMergedRegions() throws Exception {
    final TableName tableName = TableName.valueOf(this.name.getMethodName());
    String snapshotName = tableName.getNameAsString() + "_snapshot";
    Configuration conf = UTIL.getConfiguration();
    try (Admin admin = UTIL.getConnection().getAdmin()) {
      // disable compaction
      admin.compactionSwitch(false,
        admin.getRegionServers().stream().map(s -> s.getServerName()).collect(Collectors.toList()));
      // create table
      Table table = UTIL.createTable(tableName, FAMILIES, 1, bbb, yyy, 3);
      List<RegionInfo> regions = admin.getRegions(tableName);
      Assert.assertEquals(3, regions.size());
      // write some data
      UTIL.loadTable(table, FAMILIES);
      // merge region
      admin.mergeRegionsAsync(new byte[][] { regions.get(0).getEncodedNameAsBytes(),
        regions.get(1).getEncodedNameAsBytes() }, false).get();
      regions = admin.getRegions(tableName);
      Assert.assertEquals(2, regions.size());
      // snapshot
      admin.snapshot(snapshotName, tableName);
      // verify snapshot
      try (TableSnapshotScanner scanner =
        new TableSnapshotScanner(conf, UTIL.getDataTestDirOnTestFS(snapshotName), snapshotName,
          new Scan().withStartRow(bbb).withStopRow(yyy))) {
        verifyScanner(scanner, bbb, yyy);
      }
      // drop table
      admin.disableTable(tableName);
      admin.deleteTable(tableName);
      // verify snapshot
      try (TableSnapshotScanner scanner =
        new TableSnapshotScanner(conf, UTIL.getDataTestDirOnTestFS(snapshotName), snapshotName,
          new Scan().withStartRow(bbb).withStopRow(yyy))) {
        verifyScanner(scanner, bbb, yyy);
      }
    }
  }

  private void traverseAndSetFileTime(Path path, long time) throws IOException {
    fs.setTimes(path, time, -1);
    if (fs.isDirectory(path)) {
      List<FileStatus> allPaths = Arrays.asList(fs.listStatus(path));
      List<FileStatus> subDirs =
        allPaths.stream().filter(FileStatus::isDirectory).collect(Collectors.toList());
      List<FileStatus> files =
        allPaths.stream().filter(FileStatus::isFile).collect(Collectors.toList());
      for (FileStatus subDir : subDirs) {
        traverseAndSetFileTime(subDir.getPath(), time);
      }
      for (FileStatus file : files) {
        fs.setTimes(file.getPath(), time, -1);
      }
    }
  }
}
