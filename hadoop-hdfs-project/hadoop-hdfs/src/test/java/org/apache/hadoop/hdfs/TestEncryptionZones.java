/**
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
package org.apache.hadoop.hdfs;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.io.StringReader;
import java.io.StringWriter;
import java.security.PrivilegedExceptionAction;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.google.common.collect.Lists;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.crypto.CipherSuite;
import org.apache.hadoop.crypto.key.JavaKeyStoreProvider;
import org.apache.hadoop.crypto.key.KeyProvider;
import org.apache.hadoop.crypto.key.KeyProviderCryptoExtension;
import org.apache.hadoop.crypto.key.KeyProviderFactory;
import org.apache.hadoop.fs.FSTestWrapper;
import org.apache.hadoop.fs.FileContext;
import org.apache.hadoop.fs.FileContextTestWrapper;
import org.apache.hadoop.fs.FileEncryptionInfo;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FileSystemTestHelper;
import org.apache.hadoop.fs.FileSystemTestWrapper;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RemoteIterator;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.hdfs.client.HdfsAdmin;
import org.apache.hadoop.hdfs.protocol.EncryptionZone;
import org.apache.hadoop.hdfs.protocol.LocatedBlocks;
import org.apache.hadoop.hdfs.protocol.HdfsConstants.SafeModeAction;
import org.apache.hadoop.hdfs.server.namenode.EncryptionFaultInjector;
import org.apache.hadoop.hdfs.server.namenode.EncryptionZoneManager;
import org.apache.hadoop.hdfs.server.namenode.FSImageTestUtil;
import org.apache.hadoop.hdfs.server.namenode.NamenodeFsck;
import org.apache.hadoop.hdfs.tools.DFSck;
import org.apache.hadoop.hdfs.tools.offlineImageViewer.PBImageXmlWriter;
import org.apache.hadoop.security.AccessControlException;
import org.apache.hadoop.security.Credentials;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.util.ToolRunner;
import org.apache.hadoop.crypto.key.KeyProviderDelegationTokenExtension.DelegationTokenExtension;
import org.apache.hadoop.crypto.key.KeyProviderCryptoExtension.CryptoExtension;
import org.apache.hadoop.io.Text;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import static org.mockito.Mockito.withSettings;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;

import static org.apache.hadoop.hdfs.DFSTestUtil.verifyFilesEqual;
import static org.apache.hadoop.test.GenericTestUtils.assertExceptionContains;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

public class TestEncryptionZones {

  private Configuration conf;
  private FileSystemTestHelper fsHelper;

  private MiniDFSCluster cluster;
  private HdfsAdmin dfsAdmin;
  private DistributedFileSystem fs;
  private File testRootDir;
  private final String TEST_KEY = "testKey";

  protected FileSystemTestWrapper fsWrapper;
  protected FileContextTestWrapper fcWrapper;

  @Before
  public void setup() throws Exception {
    conf = new HdfsConfiguration();
    fsHelper = new FileSystemTestHelper();
    // Set up java key store
    String testRoot = fsHelper.getTestRootDir();
    testRootDir = new File(testRoot).getAbsoluteFile();
    final Path jksPath = new Path(testRootDir.toString(), "test.jks");
    conf.set(KeyProviderFactory.KEY_PROVIDER_PATH,
        JavaKeyStoreProvider.SCHEME_NAME + "://file" + jksPath.toUri()
    );
    conf.setBoolean(DFSConfigKeys.DFS_NAMENODE_DELEGATION_TOKEN_ALWAYS_USE_KEY, true);
    // Lower the batch size for testing
    conf.setInt(DFSConfigKeys.DFS_NAMENODE_LIST_ENCRYPTION_ZONES_NUM_RESPONSES,
        2);
    cluster = new MiniDFSCluster.Builder(conf).numDataNodes(1).build();
    Logger.getLogger(EncryptionZoneManager.class).setLevel(Level.TRACE);
    fs = cluster.getFileSystem();
    fsWrapper = new FileSystemTestWrapper(fs);
    fcWrapper = new FileContextTestWrapper(
        FileContext.getFileContext(cluster.getURI(), conf));
    dfsAdmin = new HdfsAdmin(cluster.getURI(), conf);
    // Need to set the client's KeyProvider to the NN's for JKS,
    // else the updates do not get flushed properly
    fs.getClient().provider = cluster.getNameNode().getNamesystem()
        .getProvider();
    // Create a test key
    DFSTestUtil.createKey(TEST_KEY, cluster, conf);
  }

  @After
  public void teardown() {
    if (cluster != null) {
      cluster.shutdown();
    }
    EncryptionFaultInjector.instance = new EncryptionFaultInjector();
  }

  public void assertNumZones(final int numZones) throws IOException {
    RemoteIterator<EncryptionZone> it = dfsAdmin.listEncryptionZones();
    int count = 0;
    while (it.hasNext()) {
      count++;
      it.next();
    }
    assertEquals("Unexpected number of encryption zones!", numZones, count);
  }

  /**
   * Checks that an encryption zone with the specified keyName and path (if not
   * null) is present.
   *
   * @throws IOException if a matching zone could not be found
   */
  public void assertZonePresent(String keyName, String path) throws IOException {
    final RemoteIterator<EncryptionZone> it = dfsAdmin.listEncryptionZones();
    boolean match = false;
    while (it.hasNext()) {
      EncryptionZone zone = it.next();
      boolean matchKey = (keyName == null);
      boolean matchPath = (path == null);
      if (keyName != null && zone.getKeyName().equals(keyName)) {
        matchKey = true;
      }
      if (path != null && zone.getPath().equals(path)) {
        matchPath = true;
      }
      if (matchKey && matchPath) {
        match = true;
        break;
      }
    }
    assertTrue("Did not find expected encryption zone with keyName " + keyName +
            " path " + path, match
    );
  }

  @Test(timeout = 60000)
  public void testBasicOperations() throws Exception {

    int numZones = 0;

    /* Test failure of create EZ on a directory that doesn't exist. */
    final Path zoneParent = new Path("/zones");
    final Path zone1 = new Path(zoneParent, "zone1");
    try {
      dfsAdmin.createEncryptionZone(zone1, TEST_KEY);
      fail("expected /test doesn't exist");
    } catch (IOException e) {
      assertExceptionContains("cannot find", e);
    }

    /* Normal creation of an EZ */
    fsWrapper.mkdir(zone1, FsPermission.getDirDefault(), true);
    dfsAdmin.createEncryptionZone(zone1, TEST_KEY);
    assertNumZones(++numZones);
    assertZonePresent(null, zone1.toString());

    /* Test failure of create EZ on a directory which is already an EZ. */
    try {
      dfsAdmin.createEncryptionZone(zone1, TEST_KEY);
    } catch (IOException e) {
      assertExceptionContains("already in an encryption zone", e);
    }

    /* Test failure of create EZ operation in an existing EZ. */
    final Path zone1Child = new Path(zone1, "child");
    fsWrapper.mkdir(zone1Child, FsPermission.getDirDefault(), false);
    try {
      dfsAdmin.createEncryptionZone(zone1Child, TEST_KEY);
      fail("EZ in an EZ");
    } catch (IOException e) {
      assertExceptionContains("already in an encryption zone", e);
    }

    /* create EZ on parent of an EZ should fail */
    try {
      dfsAdmin.createEncryptionZone(zoneParent, TEST_KEY);
      fail("EZ over an EZ");
    } catch (IOException e) {
      assertExceptionContains("encryption zone for a non-empty directory", e);
    }

    /* create EZ on a folder with a folder fails */
    final Path notEmpty = new Path("/notEmpty");
    final Path notEmptyChild = new Path(notEmpty, "child");
    fsWrapper.mkdir(notEmptyChild, FsPermission.getDirDefault(), true);
    try {
      dfsAdmin.createEncryptionZone(notEmpty, TEST_KEY);
      fail("Created EZ on an non-empty directory with folder");
    } catch (IOException e) {
      assertExceptionContains("create an encryption zone", e);
    }
    fsWrapper.delete(notEmptyChild, false);

    /* create EZ on a folder with a file fails */
    fsWrapper.createFile(notEmptyChild);
    try {
      dfsAdmin.createEncryptionZone(notEmpty, TEST_KEY);
      fail("Created EZ on an non-empty directory with file");
    } catch (IOException e) {
      assertExceptionContains("create an encryption zone", e);
    }

    /* Test failure of create EZ on a file. */
    try {
      dfsAdmin.createEncryptionZone(notEmptyChild, TEST_KEY);
      fail("Created EZ on a file");
    } catch (IOException e) {
      assertExceptionContains("create an encryption zone for a file.", e);
    }

    /* Test failure of creating an EZ passing a key that doesn't exist. */
    final Path zone2 = new Path("/zone2");
    fsWrapper.mkdir(zone2, FsPermission.getDirDefault(), false);
    final String myKeyName = "mykeyname";
    try {
      dfsAdmin.createEncryptionZone(zone2, myKeyName);
      fail("expected key doesn't exist");
    } catch (IOException e) {
      assertExceptionContains("doesn't exist.", e);
    }

    /* Test failure of empty and null key name */
    try {
      dfsAdmin.createEncryptionZone(zone2, "");
      fail("created a zone with empty key name");
    } catch (IOException e) {
      assertExceptionContains("Must specify a key name when creating", e);
    }
    try {
      dfsAdmin.createEncryptionZone(zone2, null);
      fail("created a zone with null key name");
    } catch (IOException e) {
      assertExceptionContains("Must specify a key name when creating", e);
    }

    assertNumZones(1);

    /* Test success of creating an EZ when they key exists. */
    DFSTestUtil.createKey(myKeyName, cluster, conf);
    dfsAdmin.createEncryptionZone(zone2, myKeyName);
    assertNumZones(++numZones);
    assertZonePresent(myKeyName, zone2.toString());

    /* Test failure of create encryption zones as a non super user. */
    final UserGroupInformation user = UserGroupInformation.
        createUserForTesting("user", new String[] { "mygroup" });
    final Path nonSuper = new Path("/nonSuper");
    fsWrapper.mkdir(nonSuper, FsPermission.getDirDefault(), false);

    user.doAs(new PrivilegedExceptionAction<Object>() {
      @Override
      public Object run() throws Exception {
        final HdfsAdmin userAdmin =
            new HdfsAdmin(FileSystem.getDefaultUri(conf), conf);
        try {
          userAdmin.createEncryptionZone(nonSuper, TEST_KEY);
          fail("createEncryptionZone is superuser-only operation");
        } catch (AccessControlException e) {
          assertExceptionContains("Superuser privilege is required", e);
        }
        return null;
      }
    });

    // Test success of creating an encryption zone a few levels down.
    Path deepZone = new Path("/d/e/e/p/zone");
    fsWrapper.mkdir(deepZone, FsPermission.getDirDefault(), true);
    dfsAdmin.createEncryptionZone(deepZone, TEST_KEY);
    assertNumZones(++numZones);
    assertZonePresent(null, deepZone.toString());

    // Create and list some zones to test batching of listEZ
    for (int i=1; i<6; i++) {
      final Path zonePath = new Path("/listZone" + i);
      fsWrapper.mkdir(zonePath, FsPermission.getDirDefault(), false);
      dfsAdmin.createEncryptionZone(zonePath, TEST_KEY);
      numZones++;
      assertNumZones(numZones);
      assertZonePresent(null, zonePath.toString());
    }

    fs.setSafeMode(SafeModeAction.SAFEMODE_ENTER);
    fs.saveNamespace();
    fs.setSafeMode(SafeModeAction.SAFEMODE_LEAVE);
    cluster.restartNameNode(true);
    assertNumZones(numZones);
    assertZonePresent(null, zone1.toString());
  }

  /**
   * Test listing encryption zones as a non super user.
   */
  @Test(timeout = 60000)
  public void testListEncryptionZonesAsNonSuperUser() throws Exception {

    final UserGroupInformation user = UserGroupInformation.
        createUserForTesting("user", new String[] { "mygroup" });

    final Path testRoot = new Path("/tmp/TestEncryptionZones");
    final Path superPath = new Path(testRoot, "superuseronly");
    final Path allPath = new Path(testRoot, "accessall");

    fsWrapper.mkdir(superPath, new FsPermission((short) 0700), true);
    dfsAdmin.createEncryptionZone(superPath, TEST_KEY);

    fsWrapper.mkdir(allPath, new FsPermission((short) 0707), true);
    dfsAdmin.createEncryptionZone(allPath, TEST_KEY);

    user.doAs(new PrivilegedExceptionAction<Object>() {
      @Override
      public Object run() throws Exception {
        final HdfsAdmin userAdmin =
            new HdfsAdmin(FileSystem.getDefaultUri(conf), conf);
        try {
          userAdmin.listEncryptionZones();
        } catch (AccessControlException e) {
          assertExceptionContains("Superuser privilege is required", e);
        }
        return null;
      }
    });
  }

  /**
   * Test getEncryptionZoneForPath as a non super user.
   */
  @Test(timeout = 60000)
  public void testGetEZAsNonSuperUser() throws Exception {

    final UserGroupInformation user = UserGroupInformation.
            createUserForTesting("user", new String[] { "mygroup" });

    final Path testRoot = new Path("/tmp/TestEncryptionZones");
    final Path superPath = new Path(testRoot, "superuseronly");
    final Path superPathFile = new Path(superPath, "file1");
    final Path allPath = new Path(testRoot, "accessall");
    final Path allPathFile = new Path(allPath, "file1");
    final Path nonEZDir = new Path(testRoot, "nonEZDir");
    final Path nonEZFile = new Path(nonEZDir, "file1");
    final Path nonexistent = new Path("/nonexistent");
    final int len = 8192;

    fsWrapper.mkdir(testRoot, new FsPermission((short) 0777), true);
    fsWrapper.mkdir(superPath, new FsPermission((short) 0700), false);
    fsWrapper.mkdir(allPath, new FsPermission((short) 0777), false);
    fsWrapper.mkdir(nonEZDir, new FsPermission((short) 0777), false);
    dfsAdmin.createEncryptionZone(superPath, TEST_KEY);
    dfsAdmin.createEncryptionZone(allPath, TEST_KEY);
    dfsAdmin.allowSnapshot(new Path("/"));
    final Path newSnap = fs.createSnapshot(new Path("/"));
    DFSTestUtil.createFile(fs, superPathFile, len, (short) 1, 0xFEED);
    DFSTestUtil.createFile(fs, allPathFile, len, (short) 1, 0xFEED);
    DFSTestUtil.createFile(fs, nonEZFile, len, (short) 1, 0xFEED);

    user.doAs(new PrivilegedExceptionAction<Object>() {
      @Override
      public Object run() throws Exception {
        final HdfsAdmin userAdmin =
            new HdfsAdmin(FileSystem.getDefaultUri(conf), conf);

        // Check null arg
        try {
          userAdmin.getEncryptionZoneForPath(null);
          fail("should have thrown NPE");
        } catch (NullPointerException e) {
          /*
           * IWBNI we could use assertExceptionContains, but the NPE that is
           * thrown has no message text.
           */
        }

        // Check operation with accessible paths
        assertEquals("expected ez path", allPath.toString(),
            userAdmin.getEncryptionZoneForPath(allPath).getPath().
            toString());
        assertEquals("expected ez path", allPath.toString(),
            userAdmin.getEncryptionZoneForPath(allPathFile).getPath().
            toString());

        // Check operation with inaccessible (lack of permissions) path
        try {
          userAdmin.getEncryptionZoneForPath(superPathFile);
          fail("expected AccessControlException");
        } catch (AccessControlException e) {
          assertExceptionContains("Permission denied:", e);
        }

        assertNull("expected null for nonexistent path",
            userAdmin.getEncryptionZoneForPath(nonexistent));

        // Check operation with non-ez paths
        assertNull("expected null for non-ez path",
            userAdmin.getEncryptionZoneForPath(nonEZDir));
        assertNull("expected null for non-ez path",
            userAdmin.getEncryptionZoneForPath(nonEZFile));

        // Check operation with snapshots
        String snapshottedAllPath = newSnap.toString() + allPath.toString();
        assertEquals("expected ez path", allPath.toString(),
            userAdmin.getEncryptionZoneForPath(
                new Path(snapshottedAllPath)).getPath().toString());

        /*
         * Delete the file from the non-snapshot and test that it is still ok
         * in the ez.
         */
        fs.delete(allPathFile, false);
        assertEquals("expected ez path", allPath.toString(),
            userAdmin.getEncryptionZoneForPath(
                new Path(snapshottedAllPath)).getPath().toString());

        // Delete the ez and make sure ss's ez is still ok.
        fs.delete(allPath, true);
        assertEquals("expected ez path", allPath.toString(),
            userAdmin.getEncryptionZoneForPath(
                new Path(snapshottedAllPath)).getPath().toString());
        assertNull("expected null for deleted file path",
            userAdmin.getEncryptionZoneForPath(allPathFile));
        assertNull("expected null for deleted directory path",
            userAdmin.getEncryptionZoneForPath(allPath));
        return null;
      }
    });
  }

  /**
   * Test success of Rename EZ on a directory which is already an EZ.
   */
  private void doRenameEncryptionZone(FSTestWrapper wrapper) throws Exception {
    final Path testRoot = new Path("/tmp/TestEncryptionZones");
    final Path pathFoo = new Path(testRoot, "foo");
    final Path pathFooBaz = new Path(pathFoo, "baz");
    final Path pathFooBazFile = new Path(pathFooBaz, "file");
    final Path pathFooBar = new Path(pathFoo, "bar");
    final Path pathFooBarFile = new Path(pathFooBar, "file");
    final int len = 8192;
    wrapper.mkdir(pathFoo, FsPermission.getDirDefault(), true);
    dfsAdmin.createEncryptionZone(pathFoo, TEST_KEY);
    wrapper.mkdir(pathFooBaz, FsPermission.getDirDefault(), true);
    DFSTestUtil.createFile(fs, pathFooBazFile, len, (short) 1, 0xFEED);
    String contents = DFSTestUtil.readFile(fs, pathFooBazFile);
    try {
      wrapper.rename(pathFooBaz, testRoot);
    } catch (IOException e) {
      assertExceptionContains(pathFooBaz.toString() + " can't be moved from" +
              " an encryption zone.", e
      );
    }

    // Verify that we can rename dir and files within an encryption zone.
    assertTrue(fs.rename(pathFooBaz, pathFooBar));
    assertTrue("Rename of dir and file within ez failed",
        !wrapper.exists(pathFooBaz) && wrapper.exists(pathFooBar));
    assertEquals("Renamed file contents not the same",
        contents, DFSTestUtil.readFile(fs, pathFooBarFile));
  }

  @Test(timeout = 60000)
  public void testRenameFileSystem() throws Exception {
    doRenameEncryptionZone(fsWrapper);
  }

  @Test(timeout = 60000)
  public void testRenameFileContext() throws Exception {
    doRenameEncryptionZone(fcWrapper);
  }

  private FileEncryptionInfo getFileEncryptionInfo(Path path) throws Exception {
    LocatedBlocks blocks = fs.getClient().getLocatedBlocks(path.toString(), 0);
    return blocks.getFileEncryptionInfo();
  }

  @Test(timeout = 120000)
  public void testReadWrite() throws Exception {
    final HdfsAdmin dfsAdmin =
        new HdfsAdmin(FileSystem.getDefaultUri(conf), conf);
    // Create a base file for comparison
    final Path baseFile = new Path("/base");
    final int len = 8192;
    DFSTestUtil.createFile(fs, baseFile, len, (short) 1, 0xFEED);
    // Create the first enc file
    final Path zone = new Path("/zone");
    fs.mkdirs(zone);
    dfsAdmin.createEncryptionZone(zone, TEST_KEY);
    final Path encFile1 = new Path(zone, "myfile");
    DFSTestUtil.createFile(fs, encFile1, len, (short) 1, 0xFEED);
    // Read them back in and compare byte-by-byte
    verifyFilesEqual(fs, baseFile, encFile1, len);
    // Roll the key of the encryption zone
    assertNumZones(1);
    String keyName = dfsAdmin.listEncryptionZones().next().getKeyName();
    cluster.getNamesystem().getProvider().rollNewVersion(keyName);
    // Read them back in and compare byte-by-byte
    verifyFilesEqual(fs, baseFile, encFile1, len);
    // Write a new enc file and validate
    final Path encFile2 = new Path(zone, "myfile2");
    DFSTestUtil.createFile(fs, encFile2, len, (short) 1, 0xFEED);
    // FEInfos should be different
    FileEncryptionInfo feInfo1 = getFileEncryptionInfo(encFile1);
    FileEncryptionInfo feInfo2 = getFileEncryptionInfo(encFile2);
    assertFalse("EDEKs should be different", Arrays
        .equals(feInfo1.getEncryptedDataEncryptionKey(),
            feInfo2.getEncryptedDataEncryptionKey()));
    assertNotEquals("Key was rolled, versions should be different",
        feInfo1.getEzKeyVersionName(), feInfo2.getEzKeyVersionName());
    // Contents still equal
    verifyFilesEqual(fs, encFile1, encFile2, len);
  }

  @Test(timeout = 60000)
  public void testCipherSuiteNegotiation() throws Exception {
    final HdfsAdmin dfsAdmin =
        new HdfsAdmin(FileSystem.getDefaultUri(conf), conf);
    final Path zone = new Path("/zone");
    fs.mkdirs(zone);
    dfsAdmin.createEncryptionZone(zone, TEST_KEY);
    // Create a file in an EZ, which should succeed
    DFSTestUtil
        .createFile(fs, new Path(zone, "success1"), 0, (short) 1, 0xFEED);
    // Pass no cipherSuites, fail
    fs.getClient().cipherSuites = Lists.newArrayListWithCapacity(0);
    try {
      DFSTestUtil.createFile(fs, new Path(zone, "fail"), 0, (short) 1, 0xFEED);
      fail("Created a file without specifying a CipherSuite!");
    } catch (UnknownCipherSuiteException e) {
      assertExceptionContains("No cipher suites", e);
    }
    // Pass some unknown cipherSuites, fail
    fs.getClient().cipherSuites = Lists.newArrayListWithCapacity(3);
    fs.getClient().cipherSuites.add(CipherSuite.UNKNOWN);
    fs.getClient().cipherSuites.add(CipherSuite.UNKNOWN);
    fs.getClient().cipherSuites.add(CipherSuite.UNKNOWN);
    try {
      DFSTestUtil.createFile(fs, new Path(zone, "fail"), 0, (short) 1, 0xFEED);
      fail("Created a file without specifying a CipherSuite!");
    } catch (UnknownCipherSuiteException e) {
      assertExceptionContains("No cipher suites", e);
    }
    // Pass some unknown and a good cipherSuites, success
    fs.getClient().cipherSuites = Lists.newArrayListWithCapacity(3);
    fs.getClient().cipherSuites.add(CipherSuite.AES_CTR_NOPADDING);
    fs.getClient().cipherSuites.add(CipherSuite.UNKNOWN);
    fs.getClient().cipherSuites.add(CipherSuite.UNKNOWN);
    DFSTestUtil
        .createFile(fs, new Path(zone, "success2"), 0, (short) 1, 0xFEED);
    fs.getClient().cipherSuites = Lists.newArrayListWithCapacity(3);
    fs.getClient().cipherSuites.add(CipherSuite.UNKNOWN);
    fs.getClient().cipherSuites.add(CipherSuite.UNKNOWN);
    fs.getClient().cipherSuites.add(CipherSuite.AES_CTR_NOPADDING);
    DFSTestUtil
        .createFile(fs, new Path(zone, "success3"), 4096, (short) 1, 0xFEED);
    // Check KeyProvider state
    // Flushing the KP on the NN, since it caches, and init a test one
    cluster.getNamesystem().getProvider().flush();
    KeyProvider provider = KeyProviderFactory.getProviders(conf).get(0);
    List<String> keys = provider.getKeys();
    assertEquals("Expected NN to have created one key per zone", 1,
        keys.size());
    List<KeyProvider.KeyVersion> allVersions = Lists.newArrayList();
    for (String key : keys) {
      List<KeyProvider.KeyVersion> versions = provider.getKeyVersions(key);
      assertEquals("Should only have one key version per key", 1,
          versions.size());
      allVersions.addAll(versions);
    }
    // Check that the specified CipherSuite was correctly saved on the NN
    for (int i = 2; i <= 3; i++) {
      FileEncryptionInfo feInfo =
          getFileEncryptionInfo(new Path(zone.toString() +
              "/success" + i));
      assertEquals(feInfo.getCipherSuite(), CipherSuite.AES_CTR_NOPADDING);
    }
  }

  @Test(timeout = 120000)
  public void testCreateEZWithNoProvider() throws Exception {
    // Unset the key provider and make sure EZ ops don't work
    final Configuration clusterConf = cluster.getConfiguration(0);
    clusterConf.set(KeyProviderFactory.KEY_PROVIDER_PATH, "");
    cluster.restartNameNode(true);
    cluster.waitActive();
    final Path zone1 = new Path("/zone1");
    fsWrapper.mkdir(zone1, FsPermission.getDirDefault(), true);
    try {
      dfsAdmin.createEncryptionZone(zone1, TEST_KEY);
      fail("expected exception");
    } catch (IOException e) {
      assertExceptionContains("since no key provider is available", e);
    }
    final Path jksPath = new Path(testRootDir.toString(), "test.jks");
    clusterConf.set(KeyProviderFactory.KEY_PROVIDER_PATH,
        JavaKeyStoreProvider.SCHEME_NAME + "://file" + jksPath.toUri()
    );
    // Try listing EZs as well
    assertNumZones(0);
  }

  private class MyInjector extends EncryptionFaultInjector {
    int generateCount;
    CountDownLatch ready;
    CountDownLatch wait;

    public MyInjector() {
      this.ready = new CountDownLatch(1);
      this.wait = new CountDownLatch(1);
    }

    @Override
    public void startFileAfterGenerateKey() throws IOException {
      ready.countDown();
      try {
        wait.await();
      } catch (InterruptedException e) {
        throw new IOException(e);
      }
      generateCount++;
    }
  }

  private class CreateFileTask implements Callable<Void> {
    private FileSystemTestWrapper fsWrapper;
    private Path name;

    CreateFileTask(FileSystemTestWrapper fsWrapper, Path name) {
      this.fsWrapper = fsWrapper;
      this.name = name;
    }

    @Override
    public Void call() throws Exception {
      fsWrapper.createFile(name);
      return null;
    }
  }

  private class InjectFaultTask implements Callable<Void> {
    final Path zone1 = new Path("/zone1");
    final Path file = new Path(zone1, "file1");
    final ExecutorService executor = Executors.newSingleThreadExecutor();

    MyInjector injector;

    @Override
    public Void call() throws Exception {
      // Set up the injector
      injector = new MyInjector();
      EncryptionFaultInjector.instance = injector;
      Future<Void> future =
          executor.submit(new CreateFileTask(fsWrapper, file));
      injector.ready.await();
      // Do the fault
      doFault();
      // Allow create to proceed
      injector.wait.countDown();
      future.get();
      // Cleanup and postconditions
      doCleanup();
      return null;
    }

    public void doFault() throws Exception {}

    public void doCleanup() throws Exception {}
  }

  /**
   * Tests the retry logic in startFile. We release the lock while generating
   * an EDEK, so tricky things can happen in the intervening time.
   */
  @Test(timeout = 120000)
  public void testStartFileRetry() throws Exception {
    final Path zone1 = new Path("/zone1");
    final Path file = new Path(zone1, "file1");
    fsWrapper.mkdir(zone1, FsPermission.getDirDefault(), true);
    ExecutorService executor = Executors.newSingleThreadExecutor();

    // Test when the parent directory becomes an EZ
    executor.submit(new InjectFaultTask() {
      @Override
      public void doFault() throws Exception {
        dfsAdmin.createEncryptionZone(zone1, TEST_KEY);
      }
      @Override
      public void doCleanup() throws Exception {
        assertEquals("Expected a startFile retry", 2, injector.generateCount);
        fsWrapper.delete(file, false);
      }
    }).get();

    // Test when the parent directory unbecomes an EZ
    executor.submit(new InjectFaultTask() {
      @Override
      public void doFault() throws Exception {
        fsWrapper.delete(zone1, true);
      }
      @Override
      public void doCleanup() throws Exception {
        assertEquals("Expected no startFile retries", 1, injector.generateCount);
        fsWrapper.delete(file, false);
      }
    }).get();

    // Test when the parent directory becomes a different EZ
    fsWrapper.mkdir(zone1, FsPermission.getDirDefault(), true);
    final String otherKey = "otherKey";
    DFSTestUtil.createKey(otherKey, cluster, conf);
    dfsAdmin.createEncryptionZone(zone1, TEST_KEY);

    executor.submit(new InjectFaultTask() {
      @Override
      public void doFault() throws Exception {
        fsWrapper.delete(zone1, true);
        fsWrapper.mkdir(zone1, FsPermission.getDirDefault(), true);
        dfsAdmin.createEncryptionZone(zone1, otherKey);
      }
      @Override
      public void doCleanup() throws Exception {
        assertEquals("Expected a startFile retry", 2, injector.generateCount);
        fsWrapper.delete(zone1, true);
      }
    }).get();

    // Test that the retry limit leads to an error
    fsWrapper.mkdir(zone1, FsPermission.getDirDefault(), true);
    final String anotherKey = "anotherKey";
    DFSTestUtil.createKey(anotherKey, cluster, conf);
    dfsAdmin.createEncryptionZone(zone1, anotherKey);
    String keyToUse = otherKey;

    MyInjector injector = new MyInjector();
    EncryptionFaultInjector.instance = injector;
    Future<?> future = executor.submit(new CreateFileTask(fsWrapper, file));

    // Flip-flop between two EZs to repeatedly fail
    for (int i=0; i<10; i++) {
      injector.ready.await();
      fsWrapper.delete(zone1, true);
      fsWrapper.mkdir(zone1, FsPermission.getDirDefault(), true);
      dfsAdmin.createEncryptionZone(zone1, keyToUse);
      if (keyToUse == otherKey) {
        keyToUse = anotherKey;
      } else {
        keyToUse = otherKey;
      }
      injector.wait.countDown();
      injector = new MyInjector();
      EncryptionFaultInjector.instance = injector;
    }
    try {
      future.get();
      fail("Expected exception from too many retries");
    } catch (ExecutionException e) {
      assertExceptionContains(
          "Too many retries because of encryption zone operations",
          e.getCause());
    }
  }

  /**
   * Tests obtaining delegation token from stored key
   */
  @Test(timeout = 120000)
  public void testDelegationToken() throws Exception {
    UserGroupInformation.createRemoteUser("JobTracker");
    DistributedFileSystem dfs = cluster.getFileSystem();
    KeyProviderCryptoExtension keyProvider = Mockito.mock(KeyProviderCryptoExtension.class,
        withSettings().extraInterfaces(
            DelegationTokenExtension.class,
            CryptoExtension.class));
    Mockito.when(keyProvider.getConf()).thenReturn(conf);
    byte[] testIdentifier = "Test identifier for delegation token".getBytes();

    Token<?> testToken = new Token(testIdentifier, new byte[0],
        new Text(), new Text());
    Mockito.when(((DelegationTokenExtension)keyProvider).
        addDelegationTokens(anyString(), (Credentials)any())).
        thenReturn(new Token<?>[] { testToken });

    dfs.getClient().provider = keyProvider;

    Credentials creds = new Credentials();
    final Token<?> tokens[] = dfs.addDelegationTokens("JobTracker", creds);
    DistributedFileSystem.LOG.debug("Delegation tokens: " +
        Arrays.asList(tokens));
    Assert.assertEquals(2, tokens.length);
    Assert.assertEquals(tokens[1], testToken);
    Assert.assertEquals(1, creds.numberOfTokens());
  }

  /**
   * Test running fsck on a system with encryption zones.
   */
  @Test(timeout = 60000)
  public void testFsckOnEncryptionZones() throws Exception {
    final int len = 8196;
    final Path zoneParent = new Path("/zones");
    final Path zone1 = new Path(zoneParent, "zone1");
    final Path zone1File = new Path(zone1, "file");
    fsWrapper.mkdir(zone1, FsPermission.getDirDefault(), true);
    dfsAdmin.createEncryptionZone(zone1, TEST_KEY);
    DFSTestUtil.createFile(fs, zone1File, len, (short) 1, 0xFEED);
    ByteArrayOutputStream bStream = new ByteArrayOutputStream();
    PrintStream out = new PrintStream(bStream, true);
    int errCode = ToolRunner.run(new DFSck(conf, out),
        new String[]{ "/" });
    assertEquals("Fsck ran with non-zero error code", 0, errCode);
    String result = bStream.toString();
    assertTrue("Fsck did not return HEALTHY status",
        result.contains(NamenodeFsck.HEALTHY_STATUS));

    // Run fsck directly on the encryption zone instead of root
    errCode = ToolRunner.run(new DFSck(conf, out),
        new String[]{ zoneParent.toString() });
    assertEquals("Fsck ran with non-zero error code", 0, errCode);
    result = bStream.toString();
    assertTrue("Fsck did not return HEALTHY status",
        result.contains(NamenodeFsck.HEALTHY_STATUS));
  }

  /**
   * Test correctness of successive snapshot creation and deletion
   * on a system with encryption zones.
   */
  @Test(timeout = 60000)
  public void testSnapshotsOnEncryptionZones() throws Exception {
    final int len = 8196;
    final Path zoneParent = new Path("/zones");
    final Path zone = new Path(zoneParent, "zone");
    final Path zoneFile = new Path(zone, "zoneFile");
    fsWrapper.mkdir(zone, FsPermission.getDirDefault(), true);
    dfsAdmin.allowSnapshot(zoneParent);
    dfsAdmin.createEncryptionZone(zone, TEST_KEY);
    DFSTestUtil.createFile(fs, zoneFile, len, (short) 1, 0xFEED);
    String contents = DFSTestUtil.readFile(fs, zoneFile);
    final Path snap1 = fs.createSnapshot(zoneParent);
    final Path snap1Zone = new Path(snap1, zone.getName());
    assertEquals("Got unexpected ez path", zone.toString(),
        dfsAdmin.getEncryptionZoneForPath(snap1Zone).getPath().toString());

    // Now delete the encryption zone, recreate the dir, and take another snapshot
    fsWrapper.delete(zone, true);
    fsWrapper.mkdir(zone, FsPermission.getDirDefault(), true);
    final Path snap2 = fs.createSnapshot(zoneParent);
    final Path snap2Zone = new Path(snap2, zone.getName());
    assertNull("Expected null ez path",
        dfsAdmin.getEncryptionZoneForPath(snap2Zone));

    // Create the encryption zone again
    dfsAdmin.createEncryptionZone(zone, TEST_KEY);
    final Path snap3 = fs.createSnapshot(zoneParent);
    final Path snap3Zone = new Path(snap3, zone.getName());
    assertEquals("Got unexpected ez path", zone.toString(),
        dfsAdmin.getEncryptionZoneForPath(snap3Zone).getPath().toString());

    // Verify contents of the snapshotted file
    final Path snapshottedZoneFile = new Path(
        snap1.toString() + "/" + zone.getName() + "/" + zoneFile.getName());
    assertEquals("Contents of snapshotted file have changed unexpectedly",
        contents, DFSTestUtil.readFile(fs, snapshottedZoneFile));

    // Now delete the snapshots out of order and verify the zones are still correct
    fs.deleteSnapshot(zoneParent, snap2.getName());
    assertEquals("Got unexpected ez path", zone.toString(),
        dfsAdmin.getEncryptionZoneForPath(snap1Zone).getPath().toString());
    assertEquals("Got unexpected ez path", zone.toString(),
        dfsAdmin.getEncryptionZoneForPath(snap3Zone).getPath().toString());
    fs.deleteSnapshot(zoneParent, snap1.getName());
    assertEquals("Got unexpected ez path", zone.toString(),
        dfsAdmin.getEncryptionZoneForPath(snap3Zone).getPath().toString());
  }

  /**
   * Verify symlinks can be created in encryption zones and that
   * they function properly when the target is in the same
   * or different ez.
   */
  @Test(timeout = 60000)
  public void testEncryptionZonesWithSymlinks() throws Exception {
    // Verify we can create an encryption zone over both link and target
    final int len = 8192;
    final Path parent = new Path("/parent");
    final Path linkParent = new Path(parent, "symdir1");
    final Path targetParent = new Path(parent, "symdir2");
    final Path link = new Path(linkParent, "link");
    final Path target = new Path(targetParent, "target");
    fs.mkdirs(parent);
    dfsAdmin.createEncryptionZone(parent, TEST_KEY);
    fs.mkdirs(linkParent);
    fs.mkdirs(targetParent);
    DFSTestUtil.createFile(fs, target, len, (short)1, 0xFEED);
    String content = DFSTestUtil.readFile(fs, target);
    fs.createSymlink(target, link, false);
    assertEquals("Contents read from link are not the same as target",
        content, DFSTestUtil.readFile(fs, link));
    fs.delete(parent, true);

    // Now let's test when the symlink and target are in different
    // encryption zones
    fs.mkdirs(linkParent);
    fs.mkdirs(targetParent);
    dfsAdmin.createEncryptionZone(linkParent, TEST_KEY);
    dfsAdmin.createEncryptionZone(targetParent, TEST_KEY);
    DFSTestUtil.createFile(fs, target, len, (short)1, 0xFEED);
    content = DFSTestUtil.readFile(fs, target);
    fs.createSymlink(target, link, false);
    assertEquals("Contents read from link are not the same as target",
        content, DFSTestUtil.readFile(fs, link));
    fs.delete(link, true);
    fs.delete(target, true);
  }

  /**
   * Test running the OfflineImageViewer on a system with encryption zones.
   */
  @Test(timeout = 60000)
  public void testOfflineImageViewerOnEncryptionZones() throws Exception {
    final int len = 8196;
    final Path zoneParent = new Path("/zones");
    final Path zone1 = new Path(zoneParent, "zone1");
    final Path zone1File = new Path(zone1, "file");
    fsWrapper.mkdir(zone1, FsPermission.getDirDefault(), true);
    dfsAdmin.createEncryptionZone(zone1, TEST_KEY);
    DFSTestUtil.createFile(fs, zone1File, len, (short) 1, 0xFEED);
    fs.setSafeMode(SafeModeAction.SAFEMODE_ENTER, false);
    fs.saveNamespace();

    File originalFsimage = FSImageTestUtil.findLatestImageFile(FSImageTestUtil
        .getFSImage(cluster.getNameNode()).getStorage().getStorageDir(0));
    if (originalFsimage == null) {
      throw new RuntimeException("Didn't generate or can't find fsimage");
    }

    // Run the XML OIV processor
    StringWriter output = new StringWriter();
    PrintWriter pw = new PrintWriter(output);
    PBImageXmlWriter v = new PBImageXmlWriter(new Configuration(), pw);
    v.visit(new RandomAccessFile(originalFsimage, "r"));
    final String xml = output.getBuffer().toString();
    SAXParser parser = SAXParserFactory.newInstance().newSAXParser();
    parser.parse(new InputSource(new StringReader(xml)), new DefaultHandler());
  }

}
