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

package org.apache.hadoop.hdds.scm;

import org.apache.hadoop.hdds.HddsUtils;
import org.apache.hadoop.hdds.client.RatisReplicationConfig;
import org.apache.hadoop.hdds.client.ReplicationType;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.protocol.DatanodeDetails;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos.ReplicationFactor;
import org.apache.hadoop.hdds.scm.container.placement.algorithms.SCMContainerPlacementMetrics;
import org.apache.hadoop.hdds.scm.pipeline.Pipeline;
import org.apache.hadoop.hdds.scm.pipeline.PipelineManager;
import org.apache.hadoop.hdds.utils.IOUtils;
import org.apache.hadoop.metrics2.MetricsRecordBuilder;
import org.apache.hadoop.net.DNSToSwitchMapping;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.net.StaticMapping;
import org.apache.hadoop.ozone.MiniOzoneCluster;
import org.apache.hadoop.ozone.client.ObjectStore;
import org.apache.hadoop.ozone.client.OzoneBucket;
import org.apache.hadoop.ozone.client.OzoneClient;
import org.apache.hadoop.ozone.client.OzoneClientFactory;
import org.apache.hadoop.ozone.client.OzoneVolume;
import org.apache.hadoop.ozone.client.io.OzoneOutputStream;
import org.apache.hadoop.ozone.om.OMConfigKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.hadoop.fs.CommonConfigurationKeysPublic
    .NET_TOPOLOGY_NODE_SWITCH_MAPPING_IMPL_KEY;
import static org.apache.hadoop.hdds.client.ReplicationFactor.THREE;
import static org.apache.ozone.test.MetricsAsserts.getLongCounter;
import static org.apache.ozone.test.MetricsAsserts.getMetrics;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Test cases to verify the metrics exposed by SCMPipelineManager.
 */
public class TestSCMContainerPlacementPolicyMetrics {

  private MiniOzoneCluster cluster;
  private MetricsRecordBuilder metrics;
  private OzoneClient ozClient = null;
  private ObjectStore store = null;

  @BeforeEach
  public void setup() throws Exception {
    OzoneConfiguration conf = new OzoneConfiguration();
    conf.set(ScmConfigKeys.OZONE_SCM_CONTAINER_PLACEMENT_IMPL_KEY,
        "org.apache.hadoop.hdds.scm.container.placement.algorithms." +
            "SCMContainerPlacementRackAware");
    // TODO enable when RATIS-788 is fixed
    conf.setBoolean(OMConfigKeys.OZONE_OM_RATIS_ENABLE_KEY, false);
    conf.setClass(NET_TOPOLOGY_NODE_SWITCH_MAPPING_IMPL_KEY,
        StaticMapping.class, DNSToSwitchMapping.class);
    StaticMapping.addNodeToRack(NetUtils.normalizeHostNames(
        Collections.singleton(HddsUtils.getHostName(conf))).get(0),
        "/rack1");
    cluster = MiniOzoneCluster.newBuilder(conf)
        .setNumDatanodes(4)
        .setTotalPipelineNumLimit(10)
        .build();
    cluster.waitForClusterToBeReady();
    metrics = getMetrics(SCMContainerPlacementMetrics.class.getSimpleName());
    ozClient = OzoneClientFactory.getRpcClient(conf);
    store = ozClient.getObjectStore();
  }

  /**
   * Verifies container placement metric.
   */
  @Test @Timeout(unit = TimeUnit.MILLISECONDS, value = 60000)
  public void test() throws IOException, TimeoutException {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();

    String value = "sample value";
    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    volume.createBucket(bucketName);
    OzoneBucket bucket = volume.getBucket(bucketName);
    String keyName = UUID.randomUUID().toString();

    // Write data into a key
    try (OzoneOutputStream out = bucket.createKey(keyName,
        value.getBytes(UTF_8).length, ReplicationType.RATIS,
        THREE, new HashMap<>())) {
      out.write(value.getBytes(UTF_8));
    }

    // close container
    PipelineManager manager =
        cluster.getStorageContainerManager().getPipelineManager();
    List<Pipeline> pipelines = manager.getPipelines().stream().filter(p ->
        RatisReplicationConfig
            .hasFactor(p.getReplicationConfig(), ReplicationFactor.THREE))
        .collect(Collectors.toList());
    Pipeline targetPipeline = pipelines.get(0);
    List<DatanodeDetails> nodes = targetPipeline.getNodes();
    manager.closePipeline(pipelines.get(0), true);

    // kill datanode to trigger under-replicated container replication
    cluster.shutdownHddsDatanode(nodes.get(0));
    try {
      Thread.sleep(5 * 1000);
    } catch (InterruptedException e) {
    }
    cluster.getStorageContainerManager().getReplicationManager()
        .processAll();
    try {
      Thread.sleep(30 * 1000);
    } catch (InterruptedException e) {
    }

    long totalRequest = getLongCounter("DatanodeRequestCount", metrics);
    long tryCount = getLongCounter("DatanodeChooseAttemptCount", metrics);
    long sucessCount =
        getLongCounter("DatanodeChooseSuccessCount", metrics);
    long compromiseCount =
        getLongCounter("DatanodeChooseFallbackCount", metrics);

    // Seems no under-replicated closed containers get replicated
    assertEquals(0, totalRequest);
    assertEquals(0, tryCount);
    assertEquals(0, sucessCount);
    assertEquals(0, compromiseCount);
  }

  @AfterEach
  public void teardown() {
    IOUtils.closeQuietly(ozClient);
    cluster.shutdown();
  }
}
