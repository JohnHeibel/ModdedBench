// SPDX-License-Identifier: LGPL-3.0-or-later
package baritone.gtnh;

import baritone.ForgePlanningTestRunner;
import baritone.compat.BlockPos;
import baritone.compat.EnumFacing;
import baritone.compat.LegacyPlacement;
import baritone.compat.LegacyPlayerController;
import baritone.gtnh.pathing.WorkSpec.Cell;
import org.junit.*;
import java.util.*;
import static org.junit.Assert.*;

@org.junit.runner.RunWith(ForgePlanningTestRunner.class)
public class ConstructionInteractionTest {
    @BeforeClass public static void bootstrap(){
        cpw.mods.fml.common.Loader.injectData("7","99","40","1614","1.7.10","9.05",new java.io.File("."),List.of());
        net.minecraft.init.Bootstrap.func_151354_b();
    }
    @Test public void markedDoorClicksDoNotBecomeSchematicPlacements(){
        List<BlockPos> door=List.of(new BlockPos(3,64,5),new BlockPos(3,65,5));
        assertTrue(LegacyPlayerController.interacting(door,true,new BlockPos(3,65,5),false));
        // Another tick's mark, another block under the crosshair, or a sneaking click are placements.
        assertFalse(LegacyPlayerController.interacting(door,false,new BlockPos(3,64,5),false));
        assertFalse(LegacyPlayerController.interacting(door,true,new BlockPos(4,64,5),false));
        assertFalse(LegacyPlayerController.interacting(door,true,new BlockPos(3,64,5),true));
        assertFalse(LegacyPlayerController.interacting(List.of(),true,new BlockPos(3,64,5),false));
    }
    @Test public void aClickOnAnEmptyCellLandsInItAndOneOnABlockLandsBesideIt(){
        BlockPos clicked=new BlockPos(3,64,5);
        // Tall grass, a snow layer: the game puts the block where the grass was, whichever side was clicked.
        assertEquals(clicked,LegacyPlacement.landing(true,clicked,EnumFacing.UP));
        assertEquals(clicked,LegacyPlacement.landing(true,clicked,EnumFacing.NORTH));
        assertEquals(new BlockPos(3,65,5),LegacyPlacement.landing(false,clicked,EnumFacing.UP));
        assertEquals(new BlockPos(3,64,4),LegacyPlacement.landing(false,clicked,EnumFacing.NORTH));
    }
    private static Cell block(int x,int y,int z){return new Cell(new BlockPos(x,y,z),"minecraft:stone",0,false);}
    private static Cell clear(int x,int y,int z){return new Cell(new BlockPos(x,y,z),"",0,true);}
    /** The rule the job installs for the builder and for every path search, asked the way they ask it. */
    @Test public void theJobsBreakRuleIsPlanBreaksForItsCellsAndTheTerrainPermissionOutside(){
        BlockPos done=new BlockPos(0,64,0),wrong=new BlockPos(1,64,0),grass=new BlockPos(2,64,0),dig=new BlockPos(3,64,0),support=new BlockPos(4,64,0),clicked=new BlockPos(5,64,0),outside=new BlockPos(9,64,9);
        Map<BlockPos,Cell> cells=new HashMap<>();
        for(Cell c:List.of(block(0,64,0),block(1,64,0),block(2,64,0),clear(3,64,0),clear(4,64,0),block(5,64,0)))cells.put(c.pos(),c);
        Map<BlockPos,Boolean> verified=Map.of(done,true);
        for(boolean replace:new boolean[]{false,true}){
            var rule=ReferenceConstructionProcess.breakRule(cells,verified,Set.of(support),Set.of(grass),Set.of(clicked),replace,false);
            assertFalse("finished work is never dug through, replace or not (the roof corner loop)",rule.test(done));
            assertEquals("a wrong block goes only when the job replaces",replace,rule.test(wrong));
            assertTrue("what a placement would replace anyway needs no replace",rule.test(grass));
            assertTrue("a cell the plan wants empty is always dug",rule.test(dig));
            assertFalse("an access support waits for the cleanup phase",rule.test(support));
            assertFalse("a block just clicked in is left until it is seen",rule.test(clicked));
            assertFalse(rule.test(outside));
        }
        assertTrue("outside the plan the terrain permission decides",ReferenceConstructionProcess.breakRule(cells,verified,Set.of(),Set.of(),Set.of(),false,true).test(outside));
    }
    /** A merge that brings back a rule written out inside the job would leave the test above passing over code nothing calls. */
    @Test public void theJobInstallsThatRuleAndNoOther() throws java.io.IOException {
        java.nio.file.Path source=java.nio.file.Path.of("src/main/java/baritone/gtnh/ReferenceConstructionProcess.java");
        if(!java.nio.file.Files.exists(source))source=java.nio.file.Path.of("mods/baritone").resolve(source);
        String text=java.nio.file.Files.readString(source);
        java.util.regex.Matcher installs=java.util.regex.Pattern.compile("mayBreak\\s*=\\s*([^;]{0,24})").matcher(text);
        List<String> found=new ArrayList<>();while(installs.find())found.add(installs.group(1));
        assertEquals(1,found.size());
        assertTrue(found.get(0),found.get(0).startsWith("breakRule("));
        assertEquals("PlanBreaks is asked in one place",1,text.split("PlanBreaks\\.allowed\\(",-1).length-1);
    }
}
