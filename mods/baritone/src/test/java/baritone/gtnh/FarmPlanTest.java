// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package baritone.gtnh;

import baritone.ForgePlanningTestRunner;
import baritone.compat.BlockPos;
import java.util.*;
import org.junit.*;
import static org.junit.Assert.*;

@org.junit.runner.RunWith(ForgePlanningTestRunner.class)
public class FarmPlanTest {
    private static FarmPlan plan(Map<String,Object> params){
        return new FarmPlan(null,new BlockPos(0,64,0),2,params);
    }
    @Test public void pickupOnlyDisablesEveryOtherPhase(){
        var wanted=List.of(Map.of("id","test:fruit","meta",2));
        var farm=plan(Map.of("crops",List.of(),"soils",List.of(),"seeds",List.of(),"fertilizers",List.of(),"collect",wanted));
        assertEquals(0,farm.crops());
        for(String key:List.of("crops","soils","seeds","fertilizers"))assertEquals(List.of(),farm.rules().get(key));
        assertEquals(wanted,farm.rules().get("collect"));
    }
    @Test public void omittedPhasesKeepDefaultsAndEmptyCollectDisablesPickup(){
        var farm=plan(Map.of("collect",List.of()));
        assertEquals(FarmPlan.CROPS,farm.rules().get("crops"));
        assertEquals(FarmPlan.SOILS,farm.rules().get("soils"));
        assertEquals(FarmPlan.FERTILIZERS,farm.rules().get("fertilizers"));
        assertTrue(farm.rules().get("seeds") instanceof String);
        assertEquals(List.of(),farm.rules().get("collect"));
        assertTrue(plan(Map.of()).rules().get("collect") instanceof String);
    }
    @Test public void disablingOnePhaseDoesNotRelaxOtherSelectors(){
        for(String key:List.of("crops","soils","seeds","fertilizers","collect")){
            assertThrows(IllegalArgumentException.class,()->plan(Map.of(key,List.of(Map.of("unknown",true)))));
            assertThrows(IllegalArgumentException.class,()->plan(Map.of(key,Collections.nCopies(65,Map.of("id","test:item")))));
            Map<String,Object> nullValue=new HashMap<>();nullValue.put(key,null);
            assertThrows(IllegalArgumentException.class,()->plan(nullValue));
        }
        assertThrows(IllegalArgumentException.class,()->plan(Map.of("crops",List.of(Map.of("id","test:crop","meta",16)))));
        assertThrows(IllegalArgumentException.class,()->plan(Map.of("collect",List.of(Map.of("item",Map.of("id","test:item"))))));
    }
}
