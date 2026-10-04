// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh.pathing;
import baritone.compat.BlockPos;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class WorkSpecTest {
    @Test public void cellFieldsTheJobWouldIgnoreAreRejected() {
        for(var cell:List.of(Map.of("pos",List.of(1,2,3),"id","pack:block","metadata",7),Map.of("pos",List.of(1,2,3),"id","pack:block","placement",Map.of("face",2)))) {
            try{WorkSpec.cells(Map.of("cells",List.of(cell)));fail("silently accepted an ignored state constraint");}
            catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("unknown fields"));}
        }
    }
    Map<String,Object> selection(String shape){return Map.of("selection",Map.of("min",List.of(0,1,0),"max",List.of(2,3,2),"shape",shape,"block",Map.of("id","pack:block","meta",7)));}
    @Test public void inclusiveShapesAndMetadataArePreserved(){assertEquals(27,WorkSpec.cells(selection("fill")).size());assertEquals(24,WorkSpec.cells(selection("walls")).size());assertEquals(26,WorkSpec.cells(selection("shell")).size());assertTrue(WorkSpec.cells(selection("fill")).stream().allMatch(c->c.meta()==7));}
    @Test public void clearRunsTopDownAndConstructionBottomUp(){var clear=WorkSpec.cells(selection("clear"));assertEquals(3,clear.get(0).pos().getY());assertEquals(1,WorkSpec.cells(selection("fill")).get(0).pos().getY());}
    @Test public void duplicateTranslatedCellsAreRejected(){var cell=Map.of("pos",List.of(0,0,0),"id","pack:block");try{WorkSpec.cells(Map.of("origin",List.of(1,2,3),"cells",List.of(cell,cell)));fail();}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("duplicate"));}}
    @Test public void nbtAndLargeOrFractionalPlansFailBeforeWork(){for(var spec:List.of(Map.of("cells",List.of(Map.of("pos",List.of(1,2,3),"id","pack:block","tileNbt","{}"))),Map.of("selection",Map.of("min",List.of(0,1,0),"max",List.of(100,20,100),"block",Map.of("id","pack:block"))),Map.of("cells",List.of(Map.of("pos",List.of(.5,2,3),"id","pack:block"))))){try{WorkSpec.cells(WorkSpec.object(spec));fail();}catch(IllegalArgumentException expected){}}}
    @Test public void exactItemDataSurvives(){var item=Map.of("id","pack:variant","meta",200,"nbt","{mode:1}");var c=WorkSpec.cells(Map.of("origin",List.of(10,20,30),"cells",List.of(Map.of("pos",List.of(1,2,3),"id","pack:block","item",item)))).get(0);assertEquals(new BlockPos(11,22,33),c.pos());assertEquals(item,c.item());}
    /** A resume hands the saved spec back with its own options added: the ones resume accepts must not be refused here. */
    @Test public void aResumedSpecKeepsTheOptionsResumeAccepts(){
        assertEquals(1,WorkSpec.cells(Map.of("jobId","j","timeoutTicks",40,"stallTicks",400,"retry",true,"allowPlace",true,"overrideProtection",false,"cells",List.of(Map.of("pos",List.of(0,1,0),"id","pack:block")))).size());
    }
    @Test public void aCellThatNamesNoMetaAcceptsAnyAndOneThatNamesItIsExact(){
        var cells=WorkSpec.cells(Map.of("cells",List.of(Map.of("pos",List.of(0,1,0),"id","pack:block"),Map.of("pos",List.of(1,1,0),"id","pack:block","meta",0),Map.of("pos",List.of(2,1,0),"clear",true))));
        Map<Integer,Boolean> any=new HashMap<>();cells.forEach(c->any.put(c.pos().getX(),c.anyMeta()));
        assertEquals(Map.of(0,true,1,false,2,false),any);
        assertTrue("a selection's block is read the same way",WorkSpec.cells(Map.of("selection",Map.of("min",List.of(0,1,0),"max",List.of(1,1,1),"block",Map.of("id","pack:block")))).stream().allMatch(WorkSpec.Cell::anyMeta));
        assertTrue(WorkSpec.cells(selection("fill")).stream().noneMatch(WorkSpec.Cell::anyMeta));
    }
    /** One cap, on the cells a job holds: the box a selection is cut from may be larger than what it keeps. */
    @Test public void aJobTakesAtMostTheCellCap(){
        List<Map<String,Object>> cells=new ArrayList<>();
        for(int i=0;i<=WorkSpec.CELLS;i++)cells.add(Map.of("pos",List.of(i%64,1+i/4096,i/64%64),"id","pack:block"));
        assertEquals(WorkSpec.CELLS,WorkSpec.cells(Map.of("cells",cells.subList(0,WorkSpec.CELLS))).size());
        for(Map<String,Object> over:List.<Map<String,Object>>of(Map.of("cells",cells),
                Map.of("selection",Map.of("min",List.of(0,1,0),"max",List.of(16,16,16),"block",Map.of("id","pack:block"))),
                Map.of("selection",Map.of("min",List.of(0,1,0),"max",List.of(99,99,99),"shape","walls","block",Map.of("id","pack:block"))))){
            try{WorkSpec.cells(over);fail("accepted more than the cap");}catch(IllegalArgumentException expected){}
        }
        // 24 x 24 x 24 is 13,824 cells as a box and 3,176 as a shell: the shell is a job.
        assertEquals(3176,WorkSpec.cells(Map.of("selection",Map.of("min",List.of(0,1,0),"max",List.of(23,24,23),"shape","shell","block",Map.of("id","pack:block")))).size());
    }
}
