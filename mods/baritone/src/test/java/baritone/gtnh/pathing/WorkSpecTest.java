// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh.pathing;
import baritone.compat.BlockPos;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class WorkSpecTest {
    @Test public void misspelledPlacementAndCellStateAreRejected() {
        for(var cell:List.of(Map.of("pos",List.of(1,2,3),"id","pack:block","metadata",7),Map.of("pos",List.of(1,2,3),"id","pack:block","placement",Map.of("facing",2)))) {
            try{WorkSpec.cells(Map.of("cells",List.of(cell)));fail("silently accepted an ignored state constraint");}
            catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("unknown fields"));}
        }
    }
    Map<String,Object> selection(String shape){return Map.of("selection",Map.of("min",List.of(0,1,0),"max",List.of(2,3,2),"shape",shape,"block",Map.of("id","pack:block","meta",7)));}
    @Test public void inclusiveShapesAndMetadataArePreserved(){assertEquals(27,WorkSpec.cells(selection("fill")).size());assertEquals(24,WorkSpec.cells(selection("walls")).size());assertEquals(26,WorkSpec.cells(selection("shell")).size());assertTrue(WorkSpec.cells(selection("fill")).stream().allMatch(c->c.meta()==7));}
    @Test public void clearRunsTopDownAndConstructionBottomUp(){var clear=WorkSpec.cells(selection("clear"));assertEquals(3,clear.get(0).pos().getY());assertEquals(1,WorkSpec.cells(selection("fill")).get(0).pos().getY());}
    @Test public void duplicateTranslatedCellsAreRejected(){var cell=Map.of("pos",List.of(0,0,0),"id","pack:block");try{WorkSpec.cells(Map.of("origin",List.of(1,2,3),"cells",List.of(cell,cell)));fail();}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("duplicate"));}}
    @Test public void nbtAndLargeOrFractionalPlansFailBeforeWork(){for(var spec:List.of(Map.of("cells",List.of(Map.of("pos",List.of(1,2,3),"id","pack:block","tileNbt","{}"))),Map.of("selection",Map.of("min",List.of(0,1,0),"max",List.of(100,20,100),"block",Map.of("id","pack:block"))),Map.of("cells",List.of(Map.of("pos",List.of(.5,2,3),"id","pack:block"))))){try{WorkSpec.cells(WorkSpec.object(spec));fail();}catch(IllegalArgumentException expected){}}}
    @Test public void exactItemAndPlacementDataSurvive(){var item=Map.of("id","pack:variant","meta",200,"nbt","{mode:1}");var placement=Map.of("face",2,"yaw",180);var c=WorkSpec.cells(Map.of("origin",List.of(10,20,30),"cells",List.of(Map.of("pos",List.of(1,2,3),"id","pack:block","item",item,"placement",placement)))).get(0);assertEquals(new BlockPos(11,22,33),c.pos());assertEquals(item,c.item());assertEquals(placement,c.placement());}
}
