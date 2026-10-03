// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.client;

import dev.modbench.bridge.Json;
import org.junit.Test;
import static org.junit.Assert.*;

public class AttackTargetTest {
    @Test public void onlyAnAttackWithoutRetargetTakesThreeIntegers() {
        assertNull(AttackTarget.parse(Json.object("keys",new String[]{"attack"}),true));
        assertArrayEquals(new int[]{4,-5,6},AttackTarget.parse(Json.object("attackTarget",new int[]{4,-5,6}),true));
        for(var bad:new Object[][]{{"attackTarget",new int[]{4,5}},{"attackTarget",new double[]{4,5.5,6}},{"attackTarget",new Object[]{4,true,6}},{"attackTarget","4,5,6"}})
            assertThrows(IllegalArgumentException.class,()->AttackTarget.parse(Json.object(bad),true));
        assertThrows(IllegalArgumentException.class,()->AttackTarget.parse(Json.object("attackTarget",new int[]{4,5,6}),false));
        assertThrows(IllegalArgumentException.class,()->AttackTarget.parse(Json.object("attackTarget",new int[]{4,5,6},"allowRetarget",true),true));
    }
    @Test public void aLockOnAnotherBlockOrNoneIsRefusedWithBoth() {
        assertNull(AttackTarget.refusal(null,new int[]{1,2,3}));
        assertNull(AttackTarget.refusal(new int[]{4,5,6},new int[]{4,5,6}));
        var other=AttackTarget.refusal(new int[]{4,5,6},new int[]{4,5,7}).getAsJsonObject("refused");
        assertEquals("[4,5,7]",other.get("actual").toString());
        assertEquals("[4,5,6]",other.get("expected").toString());
        assertEquals("no block under the crosshair",AttackTarget.refusal(new int[]{4,5,6},null).getAsJsonObject("refused").get("actual").getAsString());
    }
}
