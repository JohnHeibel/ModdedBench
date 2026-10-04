// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package baritone.gtnh;

import org.junit.Test;
import java.util.Map;
import static org.junit.Assert.*;

public class ClickRunTest {
    @Test public void aReadEndedWithItsJobEndsItsRequestOnceAndKeepsNothingThatArrivesAfterwards(){
        var read=new ClickRun.Read();int[] ended={0};read.end=()->ended[0]++;
        assertNull(read.reply());
        read.cancel();read.reply(Map.of("values",Map.of("e0",1)));
        assertNull("a reply after the job ended is dropped",read.reply());
        read.cancel();assertEquals(1,ended[0]);
        var answered=new ClickRun.Read();answered.reply(Map.of("values",Map.of("e0",1)));
        assertEquals(Map.of("values",Map.of("e0",1)),answered.reply());
        answered.cancel();assertNull(answered.reply());
    }
}
