// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone;

import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.movement.ActionCosts;
import baritone.api.utils.PathCalculationResult;
import baritone.compat.BlockPos;
import baritone.gtnh.pathing.BuildSteps;
import baritone.gtnh.pathing.PlanBreaks;
import baritone.gtnh.pathing.WorkSpec;
import baritone.pathing.calc.AStarPathFinder;
import baritone.pathing.movement.CalculationContext;
import baritone.utils.pathing.Favoring;
import org.junit.*;
import java.util.*;
import static org.junit.Assert.*;

/**
 * The stepped order where it meets the imported builder: its own cost context and the imported A*, given what
 * ReferenceConstructionProcess gives them (a schematic of the shown cells, and placement refused in a hidden one).
 * A wall five long and three rows high on level ground, its first row laid: the second row is the current step.
 */
@org.junit.runner.RunWith(ForgePlanningTestRunner.class)
public class BuilderStepsTest {
    @BeforeClass public static void bootstrap(){
        cpw.mods.fml.common.Loader.injectData("7","99","40","1614","1.7.10","9.05",new java.io.File("."),List.of());
        net.minecraft.init.Bootstrap.func_151354_b();
    }
    @Before public void reset(){Baritone.settings().allSettings.forEach(s->s.reset());}
    private static List<WorkSpec.Cell> wall(){
        List<Map<String,Object>> cells=new ArrayList<>();
        for(int x=0;x<5;x++)for(int y=65;y<=67;y++)cells.add(Map.of("pos",List.of(x,y,0),"id","minecraft:cobblestone"));
        return WorkSpec.cells(Map.of("cells",cells));
    }
    private static BuilderTransitTest.Terrain ground(){
        var terrain=new BuilderTransitTest.Terrain();terrain.ground=64;
        for(int x=0;x<5;x++)terrain.cells.put(new BlockPos(x,65,0),net.minecraft.init.Blocks.cobblestone);
        return terrain;
    }
    /** A restricted plan (nothing placed or broken outside it) at the step the world gives. */
    private static CalculationContext context(BuilderTransitTest.Terrain terrain,List<WorkSpec.Cell> cells,BuildSteps steps) throws Exception {
        Set<BlockPos> plan=new HashSet<>();cells.forEach(c->plan.add(c.pos()));
        int[] left=new int[steps.count()];
        for(int i=0;i<cells.size();i++){var p=cells.get(i).pos();if(steps.index(i)>=0&&terrain.getBlock(p.getX(),p.getY(),p.getZ())!=net.minecraft.init.Blocks.cobblestone)left[steps.index(i)]++;}
        int step=BuildSteps.current(0,left);
        return BuilderTransitTest.builderContext(terrain,0,65,0,14,p->plan.contains(p)&&steps.visible(p,step),
            p->plan.contains(p)&&PlanBreaks.allowed(false,terrain.cells.containsKey(p),false,false,false),p->plan.contains(p)&&steps.visible(p,step));
    }
    private static PathCalculationResult climb(CalculationContext context,int feetY){
        return new AStarPathFinder(2,65,1,new GoalBlock(2,feetY,0),new Favoring(null,context),context).calculate(500,1000);
    }
    @Test public void aPlanBlockIsFreeInTheCurrentStepAndNothingGoesIntoALaterOne() throws Exception {
        var cells=wall();var terrain=ground();var context=context(terrain,cells,new BuildSteps(cells,true));
        assertEquals("the row being built is the builder's to place, at no cost",0,context.costOfPlacingAt(2,66,0,context.get(2,66,0)),0);
        assertTrue("the row above is not: neither its own block nor a throwaway",context.costOfPlacingAt(2,67,0,context.get(2,67,0))>=ActionCosts.COST_INF);
        assertTrue("outside a restricted plan, as before",context.costOfPlacingAt(2,65,1,context.get(2,65,1))>=ActionCosts.COST_INF);
        var all=context(terrain,cells,new BuildSteps(cells,false));
        assertEquals("the old order offers every cell at once",0,all.costOfPlacingAt(2,67,0,all.get(2,67,0)),0);
    }
    @Test public void aWalkMayBuildItsWayUpThroughTheCurrentStepButNotThroughTheNext() throws Exception {
        var cells=wall();var terrain=ground();var stepped=context(terrain,cells,new BuildSteps(cells,true));
        // Feet at y67 over the wall: the walk lays the row-66 block under itself. That cell is in the current step.
        assertEquals(PathCalculationResult.Type.SUCCESS_TO_GOAL,climb(stepped,67).getType());
        // Feet at y68 would need the row-67 block under them: a cell of the next step. No route, rather than an early block.
        assertEquals(PathCalculationResult.Type.FAILURE,climb(stepped,68).getType());
        assertEquals("with the order off the same walk lays both",PathCalculationResult.Type.SUCCESS_TO_GOAL,climb(context(terrain,cells,new BuildSteps(cells,false)),68).getType());
    }
}
