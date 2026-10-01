// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh;

import baritone.Baritone;
import baritone.ForgePlanningTestRunner;

import baritone.api.event.events.PathEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.input.Input;
import baritone.compat.*;
import baritone.pathing.movement.CalculationContext;
import baritone.utils.*;
import dev.modbench.api.WorldMemory;
import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraftforge.common.util.ForgeDirection;
import org.junit.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.Assert.*;

/** A paused world's planning half-tick: the custom goal's search starts and its plan is held, with no tick, key or event. */
@org.junit.runner.RunWith(ForgePlanningTestRunner.class)
public class PlanWhilePausedTest {
    @BeforeClass public static void bootstrap(){
        cpw.mods.fml.common.Loader.injectData("7","99","40","1614","1.7.10","9.05",new java.io.File("."),List.of());
        net.minecraft.init.Bootstrap.func_151354_b();
    }
    private Baritone engine;
    @Before public void reset()throws Exception{
        Baritone.settings().allSettings.forEach(s->s.reset());Baritone.settings().allowPlace.value=false;
        // The game keeps one engine per client; each test needs a fresh one.
        var primary=baritone.api.BaritoneAPI.Provider.class.getDeclaredField("primary");primary.setAccessible(true);primary.set(baritone.api.BaritoneAPI.getProvider(),null);
        engine=new Baritone();
    }
    /** Stone below y 64, air above: open ground. */
    private static final class Flat implements IBlockAccess {
        public Block getBlock(int x,int y,int z){return y<64?Blocks.STONE:Blocks.AIR;}
        public int getBlockMetadata(int x,int y,int z){return 0;}
        public TileEntity getTileEntity(int x,int y,int z){return null;}
        public int getLightBrightnessForSkyBlocks(int x,int y,int z,int min){return 15<<20|15<<4;}
        public int isBlockProvidingPowerTo(int x,int y,int z,int side){return 0;}
        public boolean isAirBlock(int x,int y,int z){return y>=64;}
        public BiomeGenBase getBiomeGenForCoords(int x,int z){return BiomeGenBase.plains;}
        public int getHeight(){return 256;}
        public boolean extendedLevelsInChunkCache(){return false;}
        public boolean isSideSolid(int x,int y,int z,ForgeDirection side,boolean fallback){return y<64;}
    }
    private static CalculationContext context(Baritone engine){
        ToolSet.Answers hand=(stacks,state)->{var out=new baritone.gtnh.ReferenceToolPolicy.Answer[stacks.length];
            Arrays.fill(out,new baritone.gtnh.ReferenceToolPolicy.Answer(0.01,true));return out;};
        return new CalculationContext(engine,true,new CalculationInputs(null,new BlockStateInterface(new Flat(),(x,z)->Math.abs(x)<64&&Math.abs(z)<64),
            new ToolSet(new ItemStack[9],0,1,hand),false,false,true,0,0,new WorldMemory.Snapshot(0,Map.of(),Map.of(),Map.of()),false,p->true));
    }
    private static void awaitPlan(Baritone engine)throws InterruptedException{
        long until=System.nanoTime()+30_000_000_000L;
        while(engine.getPathingBehavior().getInProgress().isPresent()&&System.nanoTime()<until)Thread.sleep(5);
        assertFalse("search still running",engine.getPathingBehavior().getInProgress().isPresent());
    }

    @Test public void theSearchRunsAndItsPlanIsHeldWithNoTickKeyOrEvent()throws Exception{
        var pathing=engine.getPathingBehavior();
        List<PathEvent> events=new CopyOnWriteArrayList<>();
        engine.getGameEventHandler().registerEventListener(new AbstractGameEventListener(){@Override public void onPathEvent(PathEvent e){events.add(e);}});
        engine.getCustomGoalProcess().setGoalAndPath(new GoalBlock(8,64,0));
        var start=new BetterBlockPos(0,64,0);int[] contexts={0};long before=pathing.calculationsStarted();
        assertTrue(pathing.planWhilePaused(start,()->{contexts[0]++;return context(engine);}));
        assertEquals(1,contexts[0]);assertEquals(before+1,pathing.calculationsStarted());
        // The process's first command was taken, so the first tick does not start the search again.
        assertFalse(engine.getCustomGoalProcess().pathRequested());assertTrue(engine.getCustomGoalProcess().isActive());
        awaitPlan(engine);
        var plan=pathing.getCurrent();
        assertNotNull("the plan is held for the first tick",plan);
        assertEquals(start,plan.getPath().getSrc());assertEquals(new BetterBlockPos(8,64,0),plan.getPath().getDest());
        assertEquals("not followed yet",0,plan.getPosition());
        assertTrue("events wait for the tick: "+events,events.isEmpty());
        for(Input input:Input.values())assertFalse(input.name(),engine.getInputOverrideHandler().isInputForcedDown(input));
        // Once planned, nothing starts again: no second search, no second context.
        assertFalse(pathing.planWhilePaused(start,()->{contexts[0]++;return context(engine);}));
        assertEquals(1,contexts[0]);assertEquals(before+1,pathing.calculationsStarted());
        assertSame(plan,pathing.getCurrent());
    }

    @Test public void pausedFramesAreNoTicksAndTheFirstTickIsMeasured()throws Exception{
        var pathing=engine.getPathingBehavior();var start=new BetterBlockPos(0,64,0);
        engine.getCustomGoalProcess().setGoalAndPath(new GoalBlock(8,64,0));
        var first=new FirstTick(engine,.5,64,.5);
        assertTrue(first.planning(0));
        // However many paused frames plan, the job is still at tick 0: its deadline, stall and step count only ticks.
        for(int frame=0;frame<5;frame++)first.plan(()->pathing.planWhilePaused(start,()->context(engine)));
        assertEquals(1,pathing.calculationsStarted());
        awaitPlan(engine);
        assertFalse("planned: the first tick is not held longer",first.planning(0));
        assertFalse("a job that has ticked never holds the world",new FirstTick(engine,0,0,0).planning(1));
        // Tick 1 follows the held plan, and the player has moved when tick 2 starts.
        first.before(1,.5,64,.5);first.after(1);first.before(2,1.3,64,.5);
        Map<String,Object> out=new LinkedHashMap<>();first.status(out);
        assertEquals(Map.of("searchStartedPaused",true,"planReadyAtFirstTick",true,"firstPathTick",1,"firstMovedTick",1),out);
    }

    @Test public void nothingIsPlannedUnlessTheCustomGoalAloneAsked()throws Exception{
        var pathing=engine.getPathingBehavior();var start=new BetterBlockPos(0,64,0);
        // No goal asked for.
        long before=pathing.calculationsStarted();
        assertFalse(pathing.planWhilePaused(start,()->context(engine)));
        assertEquals(before,pathing.calculationsStarted());
        // Already standing in the goal: the command is taken (the first tick then ends the process) but nothing is searched.
        engine.getCustomGoalProcess().setGoalAndPath(new GoalBlock(0,64,0));
        assertFalse(pathing.planWhilePaused(start,()->{throw new AssertionError("no context for a reached goal");}));
        assertEquals(before,pathing.calculationsStarted());
        // Another process in control: the paused world leaves the choice to its tick.
        engine.getCustomGoalProcess().setGoalAndPath(new GoalBlock(8,64,0));
        engine.getExploreProcess().explore(0,0);
        assertFalse(pathing.planWhilePaused(start,()->{throw new AssertionError("no context while another process is active");}));
        assertTrue(engine.getCustomGoalProcess().pathRequested());
    }
}
