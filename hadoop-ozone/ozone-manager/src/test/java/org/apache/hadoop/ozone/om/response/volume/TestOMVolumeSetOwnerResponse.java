/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.ozone.om.response.volume;

import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.ozone.om.OMConfigKeys;
import org.apache.hadoop.ozone.om.OMMetadataManager;
import org.apache.hadoop.ozone.om.OmMetadataManagerImpl;
import org.apache.hadoop.ozone.om.helpers.OmVolumeArgs;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos
    .CreateVolumeResponse;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos
    .OMResponse;
import org.apache.hadoop.ozone.storage.proto.OzoneManagerStorageProtos.PersistedUserVolumeInfo;
import org.apache.hadoop.util.Time;
import org.apache.hadoop.hdds.utils.db.BatchOperation;
import org.apache.hadoop.hdds.utils.db.Table;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * This class tests OMVolumeCreateResponse.
 */
public class TestOMVolumeSetOwnerResponse {

  @TempDir
  private Path folder;

  private OMMetadataManager omMetadataManager;
  private BatchOperation batchOperation;

  @BeforeEach
  public void setup() throws Exception {
    OzoneConfiguration ozoneConfiguration = new OzoneConfiguration();
    ozoneConfiguration.set(OMConfigKeys.OZONE_OM_DB_DIRS,
        folder.toAbsolutePath().toString());
    omMetadataManager = new OmMetadataManagerImpl(ozoneConfiguration, null);
    batchOperation = omMetadataManager.getStore().initBatchOperation();
  }

  @AfterEach
  public void tearDown() {
    if (batchOperation != null) {
      batchOperation.close();
    }
  }


  @Test
  public void testAddToDBBatch() throws Exception {

    String volumeName = UUID.randomUUID().toString();
    String oldOwner = "user1";
    PersistedUserVolumeInfo volumeList = PersistedUserVolumeInfo.newBuilder()
        .setObjectID(1)
        .setUpdateID(1)
        .addVolumeNames(volumeName).build();

    OMResponse omResponse = OMResponse.newBuilder()
        .setCmdType(OzoneManagerProtocolProtos.Type.SetVolumeProperty)
        .setStatus(OzoneManagerProtocolProtos.Status.OK)
        .setSuccess(true)
        .setCreateVolumeResponse(CreateVolumeResponse.getDefaultInstance())
        .build();

    OmVolumeArgs omVolumeArgs = OmVolumeArgs.newBuilder()
        .setOwnerName(oldOwner).setAdminName(oldOwner)
        .setVolume(volumeName).setCreationTime(Time.now()).build();
    OMVolumeCreateResponse omVolumeCreateResponse =
        new OMVolumeCreateResponse(omResponse, omVolumeArgs, volumeList);



    String newOwner = "user2";
    PersistedUserVolumeInfo newOwnerVolumeList =
        PersistedUserVolumeInfo.newBuilder()
        .setObjectID(1)
        .setUpdateID(1)
        .addVolumeNames(volumeName).build();
    PersistedUserVolumeInfo oldOwnerVolumeList =
        PersistedUserVolumeInfo.newBuilder()
        .setObjectID(2)
        .setUpdateID(2)
        .build();
    OmVolumeArgs newOwnerVolumeArgs = OmVolumeArgs.newBuilder()
        .setOwnerName(newOwner).setAdminName(newOwner)
        .setVolume(volumeName).setCreationTime(omVolumeArgs.getCreationTime())
        .build();

    OMVolumeSetOwnerResponse omVolumeSetOwnerResponse =
        new OMVolumeSetOwnerResponse(omResponse, oldOwner,  oldOwnerVolumeList,
            newOwnerVolumeList, newOwnerVolumeArgs);

    omVolumeCreateResponse.addToDBBatch(omMetadataManager, batchOperation);
    omVolumeSetOwnerResponse.addToDBBatch(omMetadataManager, batchOperation);

    // Do manual commit and see whether addToBatch is successful or not.
    omMetadataManager.getStore().commitBatchOperation(batchOperation);


    assertEquals(1,
        omMetadataManager.countRowsInTable(omMetadataManager.getVolumeTable()));

    Table.KeyValue<String, OmVolumeArgs> keyValue =
        omMetadataManager.getVolumeTable().iterator().next();

    assertEquals(omMetadataManager.getVolumeKey(volumeName),
        keyValue.getKey());
    assertEquals(newOwnerVolumeArgs, keyValue.getValue());

    assertEquals(volumeList,
        omMetadataManager.getUserTable().get(
            omMetadataManager.getUserKey(newOwner)));
  }

  @Test
  public void testAddToDBBatchNoOp() throws Exception {

    OMResponse omResponse = OMResponse.newBuilder()
        .setCmdType(OzoneManagerProtocolProtos.Type.SetVolumeProperty)
        .setStatus(OzoneManagerProtocolProtos.Status.VOLUME_NOT_FOUND)
        .setSuccess(false)
        .setCreateVolumeResponse(CreateVolumeResponse.getDefaultInstance())
        .build();

    OMVolumeSetOwnerResponse omVolumeSetOwnerResponse =
        new OMVolumeSetOwnerResponse(omResponse);

    try {
      omVolumeSetOwnerResponse.checkAndUpdateDB(omMetadataManager,
          batchOperation);
      assertEquals(0, omMetadataManager.countRowsInTable(
          omMetadataManager.getVolumeTable()));
    } catch (IOException ex) {
      fail("testAddToDBBatchFailure failed");
    }

  }


}
