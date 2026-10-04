// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh.pathing;

import baritone.compat.BlockPos;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static baritone.gtnh.pathing.WorkSpec.*;

/** The stepped build order as the job uses it: steps from the plan, wrong cells counted from a world, the step read from that. */
public class BuildStepsTest {
    private static Map<String,Object> cell(int x,int y,int z,int stage){
        Map<String,Object> c=new LinkedHashMap<>(Map.of("pos",List.of(x,y,z),"id","pack:block"));if(stage>0)c.put("stage",stage);return c;
    }
    /** A 3x3 room two rows high with a roof: floor y64, wall rows y65 and y66, roof y67. One requested-air cell inside. */
    private static List<Cell> room(){
        List<Map<String,Object>> cells=new ArrayList<>();
        for(int x=0;x<3;x++)for(int z=0;z<3;z++)for(int y=64;y<=67;y++)if(y==64||y==67||x!=1||z!=1)cells.add(cell(x,y,z,0));
        cells.add(Map.of("pos",List.of(1,65,1),"clear",true));
        return WorkSpec.cells(Map.of("cells",cells));
    }
    /** What ReferenceConstructionProcess.capture counts each tick: wrong cells per step, and the first of each. */
    private static int[] left(List<Cell> cells,BuildSteps steps,Set<BlockPos> built,BlockPos[] first){
        int[] left=new int[steps.count()];
        for(int i=0;i<cells.size();i++){int at=steps.index(i);if(at>=0&&!built.contains(cells.get(i).pos())&&left[at]++==0&&first!=null)first[at]=cells.get(i).pos();}
        return left;
    }
    private static Set<BlockPos> layer(List<Cell> cells,int y){
        Set<BlockPos> out=new HashSet<>();for(Cell c:cells)if(!c.clear()&&c.pos().getY()==y)out.add(c.pos());return out;
    }
    @Test public void withNoStagesNamedAPlanIsOneStepPerLayerBottomUp(){
        var cells=room();var steps=new BuildSteps(cells);
        assertEquals(List.of(Map.of("stage",0,"y",64,"cells",9),Map.of("stage",0,"y",65,"cells",8),Map.of("stage",0,"y",66,"cells",8),Map.of("stage",0,"y",67,"cells",9)),steps.list());
        // The cell that must be empty has no step: it is shown from the start, whatever the step.
        int air=cells.indexOf(cells.stream().filter(Cell::clear).findFirst().orElseThrow());
        assertEquals(-1,steps.index(air));assertTrue(steps.visible(new BlockPos(1,65,1),0));
    }
    @Test public void aStageComesBeforeHeightAndInsideAStageTheOrderIsStillBottomUp(){
        // A base, two things standing on it and a piece between them one block up, named last although it is not the highest.
        var cells=WorkSpec.cells(Map.of("cells",List.of(cell(0,64,0,0),cell(1,64,0,0),cell(2,64,0,0),cell(0,65,0,1),cell(2,65,0,1),cell(0,66,0,1),cell(1,65,0,2))));
        var steps=new BuildSteps(cells);
        assertEquals(List.of(Map.of("stage",0,"y",64,"cells",3),Map.of("stage",1,"y",65,"cells",2),Map.of("stage",1,"y",66,"cells",1),Map.of("stage",2,"y",65,"cells",1)),steps.list());
        assertFalse("the last stage waits although its height was reached two steps ago",steps.visible(new BlockPos(1,65,0),2));
        assertTrue(steps.visible(new BlockPos(1,65,0),3));
    }
    @Test public void onlyTheCurrentStepAndEarlierOnesAreShownAndTheStepMovesOnWhenTheyMatch(){
        var cells=room();var steps=new BuildSteps(cells);Set<BlockPos> built=new HashSet<>();
        Map<BlockPos,Cell> desired=new HashMap<>();cells.forEach(c->desired.put(c.pos(),c));
        int step=BuildSteps.current(0,left(cells,steps,built,null));assertEquals(0,step);
        var shown=steps.schematic(desired,step);
        assertEquals("the floor and the cell to keep empty",10,shown.size());assertFalse(shown.containsKey(new BlockPos(0,65,0)));
        assertFalse("no scaffold and no early block goes into a cell of a later step",steps.visible(new BlockPos(0,65,0),step));
        var floor=layer(cells,64);var last=floor.iterator().next();floor.remove(last);built.addAll(floor);
        assertEquals("one floor cell short: the step holds",0,BuildSteps.current(step,left(cells,steps,built,null)));
        built.add(last);step=BuildSteps.current(step,left(cells,steps,built,null));assertEquals(1,step);
        assertEquals(18,steps.schematic(desired,step).size());assertFalse(steps.visible(new BlockPos(0,66,0),step));
        built.addAll(layer(cells,65));built.addAll(layer(cells,66));step=BuildSteps.current(step,left(cells,steps,built,null));
        assertEquals("two layers found done in one look are passed at once",3,step);
        built.addAll(layer(cells,67));step=BuildSteps.current(step,left(cells,steps,built,null));
        assertEquals(steps.count(),step);assertSame("everything is shown once the last step is done",desired,steps.schematic(desired,step));
    }
    @Test public void aJobThatStopsNamesItsStepWhatIsLeftOfItAndTheFirstCell(){
        var cells=room();var steps=new BuildSteps(cells);Set<BlockPos> built=new HashSet<>(layer(cells,64));
        built.addAll(layer(cells,65));built.removeAll(Set.of(new BlockPos(2,65,0),new BlockPos(2,65,1),new BlockPos(2,65,2)));
        BlockPos[] first=new BlockPos[steps.count()];int[] left=left(cells,steps,built,first);int step=BuildSteps.current(0,left);
        assertEquals(Map.of("stage",0,"y",65,"index",2,"of",4,"left",3,"first",List.of(2,65,0)),steps.receipt(step,left,first));
        // Nothing above the unfinished step was begun or offered: the job does not skip ahead.
        assertTrue(layer(cells,66).stream().noneMatch(p->steps.visible(p,step)));
        built.addAll(layer(cells,65));built.addAll(layer(cells,66));built.addAll(layer(cells,67));
        first=new BlockPos[steps.count()];left=left(cells,steps,built,first);
        assertEquals("a finished plan stands on its last step with nothing left",Map.of("stage",0,"y",67,"index",4,"of",4,"left",0),steps.receipt(BuildSteps.current(step,left),left,first));
    }
    @Test public void aResumedJobReadsItsStepFromTheWorld(){
        var cells=room();var steps=new BuildSteps(cells);Set<BlockPos> built=new HashSet<>(layer(cells,64));built.addAll(layer(cells,65));
        assertEquals("nothing was stored: a new job over the same world starts where the world is",2,BuildSteps.current(0,left(cells,steps,built,null)));
        built.remove(new BlockPos(1,64,1));
        assertEquals("a hole in the floor found on resume is the first step again",0,BuildSteps.current(0,left(cells,steps,built,null)));
    }
    @Test public void withinAJobTheStepOnlyGoesForward(){
        var cells=room();var steps=new BuildSteps(cells);Set<BlockPos> built=new HashSet<>(layer(cells,64));built.addAll(layer(cells,65));
        int step=BuildSteps.current(0,left(cells,steps,built,null));assertEquals(2,step);
        // A floor block is lost while the second wall row is being laid: it is repaired, and the rows above it stay shown.
        var lost=new BlockPos(0,64,2);built.remove(lost);BlockPos[] first=new BlockPos[steps.count()];int[] left=left(cells,steps,built,first);
        assertEquals(2,BuildSteps.current(step,left));assertTrue(steps.visible(lost,step));assertTrue(steps.visible(new BlockPos(0,66,0),step));
        assertEquals(Map.of("stage",0,"y",66,"index",3,"of",4,"left",9,"first",List.of(0,64,2)),steps.receipt(step,left,first));
    }
    @Test public void aStopNamesTheStepOfItsCellOrTheCurrentOne(){
        var cells=room();var steps=new BuildSteps(cells);
        assertEquals("the cell's own step",Map.of("stage",0,"y",66),steps.where(new BlockPos(0,66,0),1));
        assertEquals("no cell to blame: where the order stands",Map.of("stage",0,"y",65),steps.where(null,1));
        assertEquals("a cell to be emptied has no step of its own",Map.of("stage",0,"y",65),steps.where(new BlockPos(1,65,1),1));
        assertEquals("a finished plan stands on its last step",Map.of("stage",0,"y",67),steps.where(null,steps.count()));
        assertTrue("a plan of cells to empty has no steps",new BuildSteps(WorkSpec.cells(Map.of("cells",List.of(Map.of("pos",List.of(1,65,1),"clear",true))))).where(null,0).isEmpty());
    }
    @Test public void aWrongBlockInALaterCellWaitsForItsStepButFinishedWorkIsNeverTheWay(){
        var cells=room();var steps=new BuildSteps(cells);Map<BlockPos,Cell> desired=new HashMap<>();cells.forEach(c->desired.put(c.pos(),c));
        var later=new BlockPos(0,66,0);
        // replace_existing: the builder is not shown the cell, so it neither breaks nor replaces what stands there before its step.
        assertFalse(steps.schematic(desired,0).containsKey(later));
        // Removing is not what the order delays. The walk may still dig a wrong block out of a plan cell, as before
        // steps existed; a matching cell, of whatever step, is never dug.
        assertTrue(PlanBreaks.allowed(false,false,false,true,false));assertFalse(PlanBreaks.allowed(false,true,false,true,false));
    }
    /** Two stages, each with plain cells, click cells and uses: what one mb_build call with click cells and uses holds. */
    private static Map<String,Object> clickPlan(){
        Map<String,Object> front=Map.of("look",Map.of("toward","north"));
        return Map.of("cells",List.of(cell(0,64,0,0),cell(1,64,0,0),cell(0,65,0,0),
                Map.of("pos",List.of(1,65,0),"id","mod:machine","click",front),Map.of("pos",List.of(2,64,0),"id","mod:machine","click",front),
                cell(0,64,2,1),Map.of("pos",List.of(1,64,2),"id","mod:pipe","stage",1,"click",Map.of("face","east"))),
            "uses",List.of(Map.of("pos",List.of(1,65,0),"item",Map.of("id","mod:tool")),Map.of("pos",List.of(2,64,0),"item",Map.of("empty",true)),
                Map.of("pos",List.of(1,64,2),"item",Map.of("id","mod:tool"),"stage",1)));
    }
    @Test public void inAStageThePlainCellsComeFirstThenItsClickCellsThenItsUses(){
        var cells=WorkSpec.cells(clickPlan());var steps=new BuildSteps(cells,StepPlan.uses(clickPlan()));
        assertEquals(List.of(Map.of("stage",0,"y",64,"cells",2),Map.of("stage",0,"y",65,"cells",1),Map.of("stage",0,"phase","clicks","cells",2),Map.of("stage",0,"phase","uses","uses",2),
            Map.of("stage",1,"y",64,"cells",1),Map.of("stage",1,"phase","clicks","cells",1),Map.of("stage",1,"phase","uses","uses",1)),steps.list());
        assertEquals(List.of(BuildSteps.CELLS,BuildSteps.CELLS,BuildSteps.CLICKS,BuildSteps.USES,BuildSteps.CELLS,BuildSteps.CLICKS,BuildSteps.USES),
            java.util.stream.IntStream.range(0,steps.count()).map(steps::kind).boxed().toList());
        assertEquals(3,steps.uses(0));assertEquals(6,steps.uses(1));assertEquals(1,steps.stage(4));
        // A click cell is of its stage's click step whatever its height, and the source builder is never shown one.
        Map<BlockPos,Cell> desired=new HashMap<>();cells.forEach(c->desired.put(c.pos(),c));
        for(int step=0;step<=steps.count();step++){
            var shown=steps.schematic(desired,step);
            assertTrue("step "+step,shown.values().stream().allMatch(c->c.click()==null));
            assertEquals("step "+step,step==0?2:step<4?3:4,shown.size());
        }
        // Nor may a walk put anything into a click cell before its step.
        assertFalse(steps.visible(new BlockPos(2,64,0),1));assertTrue(steps.visible(new BlockPos(2,64,0),2));
        assertEquals(Map.of("stage",0,"phase","clicks"),steps.where(new BlockPos(1,65,0),0));
        assertEquals("a use has no cell: where the order stands",Map.of("stage",1,"phase","uses"),steps.where(null,6));
        assertTrue("a plan without clicks is shown whole when it is done, as before",new BuildSteps(room()).schematic(Map.of(),4).isEmpty());
    }
    @Test public void aResumedJobLandsInThePhaseTheWorldAndTheJournalLeaveOpen(){
        var plan=clickPlan();var cells=WorkSpec.cells(plan);var steps=new BuildSteps(cells,StepPlan.uses(plan));
        // What the job counts each tick: wrong plain cells, click cells not yet settled, uses the journal has no result for.
        assertEquals("nothing built",0,BuildSteps.current(0,new int[]{2,1,2,2,1,1,1}));
        assertEquals("plain cells stand: the click cells",2,BuildSteps.current(0,new int[]{0,0,1,2,1,1,1}));
        assertEquals("click cells stand: the uses",3,BuildSteps.current(0,new int[]{0,0,0,1,1,1,1}));
        assertEquals("the first stage is done: the next one's plain cells",4,BuildSteps.current(0,new int[]{0,0,0,0,1,1,1}));
        assertEquals(6,BuildSteps.current(0,new int[]{0,0,0,0,0,0,1}));assertEquals(7,BuildSteps.current(0,new int[7]));
        // A plain cell that breaks while the clicks run does not send the job back: it is repaired when the cells are next worked.
        assertEquals(2,BuildSteps.current(2,new int[]{1,0,1,2,1,1,1}));
        var first=new BlockPos[7];first[3]=new BlockPos(2,64,0);
        assertEquals(Map.of("stage",0,"phase","uses","index",4,"of",7,"left",1,"first",List.of(2,64,0)),steps.receipt(3,new int[]{0,0,0,1,1,1,1},first));
    }
    @Test public void stagesAreValidatedBeforeWork(){
        assertEquals(2,WorkSpec.cells(Map.of("cells",List.of(cell(0,64,0,2)))).get(0).stage());
        assertEquals(0,WorkSpec.cells(Map.of("cells",List.of(cell(0,64,0,0)))).get(0).stage());
        for(Map<String,Object> bad:List.<Map<String,Object>>of(
                Map.of("cells",List.of(Map.of("pos",List.of(0,64,0),"id","pack:block","stage",-1))),
                Map.of("cells",List.of(Map.of("pos",List.of(0,64,0),"id","pack:block","stage",BuildSteps.STAGES))),
                Map.of("cells",List.of(Map.of("pos",List.of(0,64,0),"id","pack:block","stage",1.5))),
                Map.of("cells",List.of(Map.of("pos",List.of(0,64,0),"clear",true,"stage",1))),
                // There is one build behaviour: no mode and no settings to turn the order off with.
                Map.of("settings",Map.of("buildInSteps",false),"cells",List.of(cell(0,64,0,1))),
                Map.of("mode","builder","cells",List.of(cell(0,64,0,0))))){
            try{WorkSpec.cells(bad);fail("accepted "+bad);}catch(IllegalArgumentException expected){}
        }
    }
}
