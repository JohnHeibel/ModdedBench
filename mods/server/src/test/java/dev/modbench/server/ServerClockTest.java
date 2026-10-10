// SPDX-License-Identifier: MIT
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import dev.modbench.bridge.PauseCoordinator;
import java.io.File;
import java.nio.file.Files;
import org.junit.Test;

public class ServerClockTest {
    private static File hold(String word) throws Exception {
        File file=Files.createTempFile("modbench-hold",null).toFile();file.deleteOnExit();
        Files.write(file.toPath(),(word+"\n").getBytes());return file;
    }
    @Test public void aBackupOrCompactionHoldWhoseHolderDiedEndsAndTheOperatorsNeverDoes() throws Exception {
        long limit=PauseCoordinator.STALE_HOLD_MINUTES*60_000L;
        for(String by:new String[]{"backup","compaction"}) {
            File file=hold(by);long written=file.lastModified();
            assertEquals(by,ServerClock.holder(file,written));
            assertEquals("a live holder inside the limit keeps it",by,ServerClock.holder(file,written+limit));
            assertNull(ServerClock.holder(file,written+limit+1));
            assertFalse("the stale file is gone, so the next holder can take the hold",file.exists());
        }
        File operator=hold("operator");
        assertEquals("operator",ServerClock.holder(operator,operator.lastModified()+100*limit));
        assertTrue(operator.exists());
        File unknown=hold("Not A Word");
        assertEquals("an unreadable holder is the operator's, which never expires","operator",ServerClock.holder(unknown,unknown.lastModified()+100*limit));
        assertNull(ServerClock.holder(new File(operator.getParentFile(),"modbench-hold-absent"),0));
    }
}
