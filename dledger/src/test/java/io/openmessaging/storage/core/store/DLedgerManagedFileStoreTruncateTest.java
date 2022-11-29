/*
 * Copyright 2017-2022 The DLedger Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.openmessaging.storage.core.store;

import io.openmessaging.storage.core.ServerTestHarness;
import io.openmessaging.storage.core.util.FileTestUtil;
import io.openmessaging.storage.dledger.entry.DLedgerEntry;
import io.openmessaging.storage.dledger.DLedgerConfig;
import io.openmessaging.storage.dledger.MemberState;
import io.openmessaging.storage.dledger.store.file.DLedgerMmapFileStore;
import java.io.File;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static io.openmessaging.storage.dledger.store.file.MmapFileList.MIN_BLANK_LEN;

public class DLedgerManagedFileStoreTruncateTest extends ServerTestHarness {

    private synchronized DLedgerMmapFileStore createFileStore(String group, String peers, String selfId, String leaderId,
                                                              int dataFileSize, int indexFileSize, int deleteFileNums) {
        DLedgerConfig config = new DLedgerConfig();
        config.setStoreBaseDir(FileTestUtil.TEST_BASE + File.separator + group);
        config.group(group).selfId(selfId).peers(peers);
        config.setStoreType(DLedgerConfig.MEMORY);
        config.setDiskSpaceRatioToForceClean(0.90f);
        config.setEnableDiskForceClean(false);
        config.setEnableLeaderElector(false);

        // no flush
        config.setFlushFileInterval(10000000);
        if (dataFileSize != -1) {
            config.setMappedFileSizeForEntryData(dataFileSize);
        }
        if (indexFileSize != -1) {
            config.setMappedFileSizeForEntryIndex(indexFileSize);
        }
        if (deleteFileNums > 0) {
            File dir = new File(config.getDataStorePath());
            File[] files = dir.listFiles();
            if (files != null) {
                Arrays.sort(files);
                for (int i = files.length - 1; i >= 0; i--) {
                    File file = files[i];
                    file.delete();
                    if (files.length - i >= deleteFileNums) {
                        break;
                    }
                }
            }
        }

        MemberState memberState = new MemberState(config);
        memberState.setCurrTermForTest(0);
        if (selfId.equals(leaderId)) {
            memberState.changeToLeader(0);
        } else {
            memberState.changeToFollower(0, leaderId);
        }
        bases.add(config.getDataStorePath());
        bases.add(config.getIndexStorePath());
        bases.add(config.getDefaultPath());
        DLedgerMmapFileStore fileStore = new DLedgerMmapFileStore(config, memberState);
        fileStore.startup();
        return fileStore;
    }

    @Test
    public void testDataFileListFlushedPosRightAfterTruncate() {
        String group = UUID.randomUUID().toString();
        String peers = String.format("n0-localhost:%d", nextPort());
        DLedgerMmapFileStore fileStore = createFileStore(group, peers, "n0", "n0", 8 * 1024 + MIN_BLANK_LEN, 8 * DLedgerMmapFileStore.INDEX_UNIT_SIZE, 0);
        for (int i = 0; i < 7; i++) {
            DLedgerEntry entry = new DLedgerEntry();
            entry.setBody(new byte[1024]);
            DLedgerEntry resEntry = fileStore.appendAsLeader(entry);
            Assertions.assertEquals(i, resEntry.getIndex());
        }

        // fileStore flush has set very large, trigger here.
        fileStore.flush();

        // only one file and all data flush
        Assertions.assertEquals(1, fileStore.getDataFileList().getMappedFiles().size());
        Assertions.assertEquals(fileStore.getFlushPos(),
                fileStore.getDataFileList().getMaxWrotePosition());


        fileStore.getMemberState().changeToFollower(fileStore.getLedgerEndTerm(), "n0");

        {
            //truncate the mid
            DLedgerEntry midEntry = fileStore.get(5L);
            Assertions.assertNotNull(midEntry);
            long midIndex = fileStore.truncate(midEntry, fileStore.getLedgerEndTerm(), "n0");
            Assertions.assertEquals(5, midIndex);

            // check file flush position after truncate
            Assertions.assertEquals(fileStore.getDataFileList().getFlushedWhere(),
                    fileStore.getDataFileList().getMaxWrotePosition());

            // when truncate entry exist before commit 9ea565ef will always rewrite last exist index entry.
            Assertions.assertEquals(fileStore.getIndexFileList().getFlushedWhere(),
                    fileStore.getIndexFileList().getMaxWrotePosition() - DLedgerMmapFileStore.INDEX_UNIT_SIZE);
        }

    }
}
