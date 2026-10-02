// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package baritone.gtnh;

import java.util.*;
import org.junit.*;
import static org.junit.Assert.*;

public class ForcedToolJournalTest {
    @Test public void resumeKeepsKindAfterTheOriginalSlotChanges(){
        Map<String,Object> progress=new LinkedHashMap<>();
        assertEquals("test:tool:3",ReferenceToolPolicy.remember(progress,()->"test:tool:3"));
        Map<String,Object> restored=new LinkedHashMap<>(progress);
        assertEquals("test:tool:3",ReferenceToolPolicy.remember(restored,()->{throw new AssertionError("original slot must not be read again");}));
    }
    @Test public void legacyJournalResolvesOnceAndFailedResolutionDoesNotFreezeAnything(){
        Map<String,Object> progress=new LinkedHashMap<>();
        assertThrows(IllegalArgumentException.class,()->ReferenceToolPolicy.remember(progress,()->{throw new IllegalArgumentException("empty slot");}));
        assertFalse(progress.containsKey("forcedTool"));
        assertEquals("test:replacement:0",ReferenceToolPolicy.remember(progress,()->"test:replacement:0"));
    }
}
