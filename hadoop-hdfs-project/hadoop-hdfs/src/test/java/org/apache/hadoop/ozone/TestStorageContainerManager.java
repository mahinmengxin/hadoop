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
package org.apache.hadoop.ozone;

import static org.junit.Assert.fail;
import java.io.IOException;

import org.apache.commons.lang.RandomStringUtils;
import org.apache.hadoop.conf.OzoneConfiguration;
import org.apache.hadoop.ozone.protocol.proto.OzoneProtos;
import org.apache.hadoop.ozone.scm.StorageContainerManager;
import org.apache.hadoop.ozone.scm.block.DeletedBlockLog;
import org.apache.hadoop.scm.XceiverClientManager;
import org.apache.hadoop.scm.container.common.helpers.Pipeline;
import org.junit.Rule;
import org.junit.Assert;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.Collections;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.apache.hadoop.ozone.ksm.helpers.KsmKeyInfo;
import org.apache.hadoop.ozone.ksm.helpers.KsmKeyLocationInfo;
import org.apache.hadoop.scm.ScmConfigKeys;

import org.apache.hadoop.io.IOUtils;
import org.junit.rules.Timeout;
import org.mockito.Mockito;
import org.apache.hadoop.test.GenericTestUtils;

/**
 * Test class that exercises the StorageContainerManager.
 */
public class TestStorageContainerManager {
  private static XceiverClientManager xceiverClientManager =
      new XceiverClientManager(
      new OzoneConfiguration());
  /**
   * Set the timeout for every test.
   */
  @Rule
  public Timeout testTimeout = new Timeout(300000);

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @Rule
  public ExpectedException exception = ExpectedException.none();

  @Test
  public void testRpcPermission() throws IOException {
    // Test with default configuration
    OzoneConfiguration defaultConf = new OzoneConfiguration();
    testRpcPermissionWithConf(defaultConf, "unknownUser", true);

    // Test with ozone.administrators defined in configuration
    OzoneConfiguration ozoneConf = new OzoneConfiguration();
    ozoneConf.setStrings(OzoneConfigKeys.OZONE_ADMINISTRATORS,
        "adminUser1, adminUser2");
    // Non-admin user will get permission denied.
    testRpcPermissionWithConf(ozoneConf, "unknownUser", true);
    // Admin user will pass the permission check.
    testRpcPermissionWithConf(ozoneConf, "adminUser2", false);
  }

  private void testRpcPermissionWithConf(
      OzoneConfiguration ozoneConf, String fakeRemoteUsername,
      boolean expectPermissionDenied) throws IOException {
    MiniOzoneCluster cluster =
        new MiniOzoneCluster.Builder(ozoneConf).numDataNodes(1)
            .setHandlerType(OzoneConsts.OZONE_HANDLER_DISTRIBUTED).build();

    try {
      String fakeUser = fakeRemoteUsername;
      StorageContainerManager mockScm = Mockito.spy(
          cluster.getStorageContainerManager());
      Mockito.when(mockScm.getPpcRemoteUsername())
          .thenReturn(fakeUser);

      try {
        mockScm.deleteContainer("container1");
        fail("Operation should fail, expecting an IOException here.");
      } catch (Exception e) {
        if (expectPermissionDenied) {
          verifyPermissionDeniedException(e, fakeUser);
        } else {
          // If passes permission check, it should fail with
          // container not exist exception.
          Assert.assertTrue(e.getMessage()
              .contains("container doesn't exist"));
        }
      }

      try {
        Pipeline pipeLine2 = mockScm.allocateContainer(
            xceiverClientManager.getType(),
            OzoneProtos.ReplicationFactor.ONE, "container2");
        if (expectPermissionDenied) {
          fail("Operation should fail, expecting an IOException here.");
        } else {
          Assert.assertEquals("container2", pipeLine2.getContainerName());
        }
      } catch (Exception e) {
        verifyPermissionDeniedException(e, fakeUser);
      }

      try {
        Pipeline pipeLine3 = mockScm.allocateContainer(
            xceiverClientManager.getType(),
            OzoneProtos.ReplicationFactor.ONE, "container3");

        if (expectPermissionDenied) {
          fail("Operation should fail, expecting an IOException here.");
        } else {
          Assert.assertEquals("container3", pipeLine3.getContainerName());
          Assert.assertEquals(1, pipeLine3.getMachines().size());
        }
      } catch (Exception e) {
        verifyPermissionDeniedException(e, fakeUser);
      }

      try {
        mockScm.getContainer("container4");
        fail("Operation should fail, expecting an IOException here.");
      } catch (Exception e) {
        if (expectPermissionDenied) {
          verifyPermissionDeniedException(e, fakeUser);
        } else {
          // If passes permission check, it should fail with
          // key not exist exception.
          Assert.assertTrue(e.getMessage()
              .contains("Specified key does not exist"));
        }
      }
    } finally {
      IOUtils.cleanupWithLogger(null, cluster);
    }
  }

  private void verifyPermissionDeniedException(Exception e, String userName) {
    String expectedErrorMessage = "Access denied for user "
        + userName + ". " + "Superuser privilege is required.";
    Assert.assertTrue(e instanceof IOException);
    Assert.assertEquals(expectedErrorMessage, e.getMessage());
  }

  @Test
  public void testBlockDeletionTransactions() throws Exception {
    OzoneConfiguration conf = new OzoneConfiguration();
    conf.setInt(ScmConfigKeys.OZONE_SCM_HEARTBEAT_INTERVAL_SECONDS, 5);
    conf.setInt(ScmConfigKeys.OZONE_SCM_HEARTBEAT_PROCESS_INTERVAL_MS, 3000);
    conf.setInt(ScmConfigKeys.OZONE_SCM_BLOCK_DELETION_MAX_RETRY, 5);
    conf.setInt(OzoneConfigKeys.OZONE_BLOCK_DELETING_SERVICE_INTERVAL_MS, 1000);
    MiniOzoneCluster cluster =
        new MiniOzoneCluster.Builder(conf).numDataNodes(1)
            .setHandlerType(OzoneConsts.OZONE_HANDLER_DISTRIBUTED).build();

    DeletedBlockLog delLog = cluster.getStorageContainerManager()
        .getScmBlockManager().getDeletedBlockLog();
    Assert.assertEquals(0, delLog.getNumOfValidTransactions());

    // Create 20 random names keys.
    TestStorageContainerManagerHelper helper =
        new TestStorageContainerManagerHelper(cluster, conf);
    Map<String, KsmKeyInfo> keyLocations = helper.createKeys(20, 4096);

    // These keys will be written into a bunch of containers,
    // gets a set of container names, verify container containerBlocks
    // on datanodes.
    Set<String> containerNames = new HashSet<>();
    for (Map.Entry<String, KsmKeyInfo> entry : keyLocations.entrySet()) {
      entry.getValue().getKeyLocationList()
          .forEach(loc -> containerNames.add(loc.getContainerName()));
    }

    // Total number of containerBlocks of these containers should be equal to
    // total number of containerBlocks via creation call.
    int totalCreatedBlocks = 0;
    for (KsmKeyInfo info : keyLocations.values()) {
      totalCreatedBlocks += info.getKeyLocationList().size();
    }
    Assert.assertTrue(totalCreatedBlocks > 0);
    Assert.assertEquals(totalCreatedBlocks,
        helper.getAllBlocks(containerNames).size());

    // Create a deletion TX for each key.
    Map<String, List<String>> containerBlocks = Maps.newHashMap();
    for (KsmKeyInfo info : keyLocations.values()) {
      List<KsmKeyLocationInfo> list = info.getKeyLocationList();
      list.forEach(location -> {
        if (containerBlocks.containsKey(location.getContainerName())) {
          containerBlocks.get(location.getContainerName())
              .add(location.getBlockID());
        } else {
          List<String> blks = Lists.newArrayList();
          blks.add(location.getBlockID());
          containerBlocks.put(location.getContainerName(), blks);
        }
      });
    }
    for (Map.Entry<String, List<String>> tx : containerBlocks.entrySet()) {
      delLog.addTransaction(tx.getKey(), tx.getValue());
    }

    // Verify a few TX gets created in the TX log.
    Assert.assertTrue(delLog.getNumOfValidTransactions() > 0);

    // Once TXs are written into the log, SCM starts to fetch TX
    // entries from the log and schedule block deletions in HB interval,
    // after sometime, all the TX should be proceed and by then
    // the number of containerBlocks of all known containers will be
    // empty again.
    GenericTestUtils.waitFor(() -> {
      try {
        return delLog.getNumOfValidTransactions() == 0;
      } catch (IOException e) {
        return false;
      }
    }, 1000, 10000);
    Assert.assertTrue(helper.getAllBlocks(containerNames).isEmpty());

    // Continue the work, add some TXs that with known container names,
    // but unknown block IDs.
    for (String containerName : containerBlocks.keySet()) {
      // Add 2 TXs per container.
      delLog.addTransaction(containerName,
          Collections.singletonList(RandomStringUtils.randomAlphabetic(5)));
      delLog.addTransaction(containerName,
          Collections.singletonList(RandomStringUtils.randomAlphabetic(5)));
    }

    // Verify a few TX gets created in the TX log.
    Assert.assertTrue(delLog.getNumOfValidTransactions() > 0);

    // These blocks cannot be found in the container, skip deleting them
    // eventually these TX will success.
    GenericTestUtils.waitFor(() -> {
      try {
        return delLog.getFailedTransactions().size() == 0;
      } catch (IOException e) {
        return false;
      }
    }, 1000, 10000);
  }
}
