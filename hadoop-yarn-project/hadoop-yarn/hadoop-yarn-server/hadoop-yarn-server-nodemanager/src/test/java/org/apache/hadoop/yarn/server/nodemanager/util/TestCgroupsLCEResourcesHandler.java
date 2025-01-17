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
package org.apache.hadoop.yarn.server.nodemanager.util;

import org.apache.commons.io.FileUtils;
import org.apache.hadoop.yarn.server.nodemanager.LinuxContainerExecutor;
import org.apache.hadoop.yarn.util.ResourceCalculatorPlugin;
import org.junit.Assert;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.util.Clock;
import org.junit.Test;
import org.mockito.Mockito;

import java.io.*;
import java.util.List;
import java.util.Scanner;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

public class TestCgroupsLCEResourcesHandler {

  static class MockClock implements Clock {
    long time;
    @Override
    public long getTime() {
      return time;
    }
  }
  @Test
  public void testDeleteCgroup() throws Exception {
    final MockClock clock = new MockClock();
    clock.time = System.currentTimeMillis();
    CgroupsLCEResourcesHandler handler = new CgroupsLCEResourcesHandler();
    handler.setConf(new YarnConfiguration());
    handler.initConfig();
    handler.clock = clock;
    
    //file exists
    File file = new File("target", UUID.randomUUID().toString());
    new FileOutputStream(file).close();
    Assert.assertTrue(handler.deleteCgroup(file.getPath()));

    //file does not exists, timing out
    final CountDownLatch latch = new CountDownLatch(1);
    new Thread() {
      @Override
      public void run() {
        latch.countDown();
        try {
          Thread.sleep(200);
        } catch (InterruptedException ex) {
          //NOP
        }
        clock.time += YarnConfiguration.
            DEFAULT_NM_LINUX_CONTAINER_CGROUPS_DELETE_TIMEOUT;
      }
    }.start();
    latch.await();
    file = new File("target", UUID.randomUUID().toString());
    Assert.assertFalse(handler.deleteCgroup(file.getPath()));
  }

  static class MockLinuxContainerExecutor extends LinuxContainerExecutor {
    @Override
    public void mountCgroups(List<String> x, String y) {
    }
  }

  static class CustomCgroupsLCEResourceHandler extends
      CgroupsLCEResourcesHandler {

    String mtabFile;
    int[] limits = new int[2];

    @Override
    int[] getOverallLimits(float x) {
      return limits;
    }

    void setMtabFile(String file) {
      mtabFile = file;
    }

    @Override
    String getMtabFileName() {
      return mtabFile;
    }
  }

  @Test
  public void testInit() throws IOException {
    LinuxContainerExecutor mockLCE = new MockLinuxContainerExecutor();
    CustomCgroupsLCEResourceHandler handler =
        new CustomCgroupsLCEResourceHandler();
    YarnConfiguration conf = new YarnConfiguration();
    final int numProcessors = 4;
    ResourceCalculatorPlugin plugin =
        Mockito.mock(ResourceCalculatorPlugin.class);
    Mockito.doReturn(numProcessors).when(plugin).getNumProcessors();
    handler.setConf(conf);
    handler.initConfig();

    // create mock cgroup
    File cgroupDir = new File("target", UUID.randomUUID().toString());
    if (!cgroupDir.mkdir()) {
      String message = "Could not create dir " + cgroupDir.getAbsolutePath();
      throw new IOException(message);
    }
    File cgroupMountDir = new File(cgroupDir.getAbsolutePath(), "hadoop-yarn");
    if (!cgroupMountDir.mkdir()) {
      String message =
          "Could not create dir " + cgroupMountDir.getAbsolutePath();
      throw new IOException(message);
    }

    // create mock mtab
    String mtabContent =
        "none " + cgroupDir.getAbsolutePath() + " cgroup rw,relatime,cpu 0 0";
    File mockMtab = new File("target", UUID.randomUUID().toString());
    if (!mockMtab.exists()) {
      if (!mockMtab.createNewFile()) {
        String message = "Could not create file " + mockMtab.getAbsolutePath();
        throw new IOException(message);
      }
    }
    FileWriter mtabWriter = new FileWriter(mockMtab.getAbsoluteFile());
    mtabWriter.write(mtabContent);
    mtabWriter.close();
    mockMtab.deleteOnExit();

    // setup our handler and call init()
    handler.setMtabFile(mockMtab.getAbsolutePath());

    // check values
    // in this case, we're using all cpu so the files
    // shouldn't exist(because init won't create them
    handler.init(mockLCE, plugin);
    File periodFile = new File(cgroupMountDir, "cpu.cfs_period_us");
    File quotaFile = new File(cgroupMountDir, "cpu.cfs_quota_us");
    Assert.assertFalse(periodFile.exists());
    Assert.assertFalse(quotaFile.exists());

    // subset of cpu being used, files should be created
    conf.setInt(YarnConfiguration.NM_RESOURCE_PERCENTAGE_PHYSICAL_CPU_LIMIT, 75);
    handler.limits[0] = 100 * 1000;
    handler.limits[1] = 1000 * 1000;
    handler.init(mockLCE, plugin);
    int period = readIntFromFile(periodFile);
    int quota = readIntFromFile(quotaFile);
    Assert.assertEquals(100 * 1000, period);
    Assert.assertEquals(1000 * 1000, quota);

    // set cpu back to 100, quota should be -1
    conf.setInt(YarnConfiguration.NM_RESOURCE_PERCENTAGE_PHYSICAL_CPU_LIMIT, 100);
    handler.limits[0] = 100 * 1000;
    handler.limits[1] = 1000 * 1000;
    handler.init(mockLCE, plugin);
    quota = readIntFromFile(quotaFile);
    Assert.assertEquals(-1, quota);

    FileUtils.deleteQuietly(cgroupDir);
  }

  private int readIntFromFile(File targetFile) throws IOException {
    Scanner scanner = new Scanner(targetFile);
    if (scanner.hasNextInt()) {
      return scanner.nextInt();
    }
    return -1;
  }

  @Test
  public void testGetOverallLimits() {

    int expectedQuota = 1000 * 1000;
    CgroupsLCEResourcesHandler handler = new CgroupsLCEResourcesHandler();

    int[] ret = handler.getOverallLimits(2);
    Assert.assertEquals(expectedQuota / 2, ret[0]);
    Assert.assertEquals(expectedQuota, ret[1]);

    ret = handler.getOverallLimits(2000);
    Assert.assertEquals(expectedQuota, ret[0]);
    Assert.assertEquals(-1, ret[1]);

    int[] params = { 0, -1 };
    for (int cores : params) {
      try {
        handler.getOverallLimits(cores);
        Assert.fail("Function call should throw error.");
      } catch (IllegalArgumentException ie) {
        // expected
      }
    }

    // test minimums
    ret = handler.getOverallLimits(1000 * 1000);
    Assert.assertEquals(1000 * 1000, ret[0]);
    Assert.assertEquals(-1, ret[1]);
  }
}
