// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package baritone.gtnh;

import baritone.ForgePlanningTestRunner;
import baritone.Planning;
import baritone.api.utils.BlockOptionalMeta;
import baritone.compat.BlockPos;
import baritone.compat.IBlockState;
import baritone.compat.LegacyPlacement;
import baritone.gtnh.ReferenceToolPolicy.Answer;
import baritone.gtnh.pathing.*;
import dev.modbench.api.*;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import java.util.*;
import java.util.concurrent.*;
import org.junit.*;
import static baritone.TickBudget.*;
import static org.junit.Assert.*;

/**
 * What our code does on the game thread in one tick, at the sizes a base has, against TickBudget.LIMIT_MS. Reached
 * without a game: the two scanners, the shape warm-up, the queued tool and pick-block asks, the copy around clicks and its hand-over, a
 * build's reads of its plan, its order at a pass start and in a preview, and the slicing of its goals. Not reached, because they need a player or
 * the walker: the job step itself (ReferenceProcessJob.tick, ReferenceConstructionProcess.step and survey, MiningProcess,
 * ClickRun.tick), the walker's own tick, the tool warm-up, WorkAccess.Stands and GoalRoom. Those are measured in the
 * game: the receipt's `cost`, which the suites in harness/smoke hold to a limit (tick_cost.py).
 */
@org.junit.runner.RunWith(ForgePlanningTestRunner.class)
public class TickBudgetTest {
    private static final BlockPos FEET=new BlockPos(8,64,8);
    private static OfflineWorld world;
    private static <T>T none(Class<T> type){return type.cast(java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(proxy,method,args)->null));}
    @BeforeClass public static void game() throws Exception {
        Planning.bootstrap();world=OfflineWorld.install(16);world.set(5,70,6,Blocks.crafting_table);
        // No protected regions: the copy around clicks asks about every solid cell.
        ControlRegistry.register(none(Controls.class),none(Targeting.class),none(PlacementInfo.class),none(MemoryAccess.class));
    }

    /** The change of 2026-10-05 that this file exists for read every loaded chunk in one tick: 35 to 300 ms. */
    @Test public void aSearchByIdReadsTheLoadedChunksASliceATick(){
        var absent=new BlockOptionalMeta(Blocks.bookshelf);
        for(int radius:new int[]{8,16}){
            var loaded=world.within(radius);String n=loaded.size()+" loaded chunks";
            // Nothing matches: every chunk is read to its end.
            check("find a block by id, worst of the first sixty ticks, no match, "+n,worstMs(()->{var scan=new ChunkObservation(absent);int[] ticks={60};return ()->{scan.tick(()->loaded,FEET);return scan.passes==0&&ticks[0]-->0;};}));
            var one=new ChunkObservation(absent);one.tick(()->loaded,FEET);
            assertEquals("one tick does not read every loaded chunk",0,one.passes);
            assertNotNull("so the source search is handed these and never sweeps the chunks itself (MineProcess.searchWorld)",one.observedLocations());
            check("find a block by id, a tick after the first match, "+n,medianMs(()->{
                var scan=new ChunkObservation(new BlockOptionalMeta(Blocks.crafting_table));scan.tick(()->loaded,FEET);assertFalse(scan.waiting());return ()->scan.tick(()->loaded,FEET);}));
            // Every chunk has sixteen: the nearest are chosen again on each tick that finds more.
            check("find a block by id, worst tick of the first pass, a common block, "+n,worstMs(()->{var scan=new ChunkObservation(new BlockOptionalMeta(Blocks.stone));return ()->{scan.tick(()->loaded,FEET);return scan.passes==0;};}));
        }
        var all=world.within(8);var sweep=new ChunkObservation(absent);
        measure("the same search unsliced, 289 loaded chunks (what the limit is there to fail)",worstMs(Double.MAX_VALUE,()->()->{var out=new ArrayList<BlockPos>();for(var c:all.values())sweep.scan(c,64,16,out);return false;}));
    }

    private static String source(String path) throws java.io.IOException {
        var file=java.nio.file.Path.of(path);return java.nio.file.Files.readString(java.nio.file.Files.exists(file)?file:java.nio.file.Path.of("mods/baritone").resolve(file));
    }
    /** The sweep is still in the source search (WorldScanner.scanChunkRadius reads every loaded chunk in the one call): the job hands it no block by id. */
    @Test public void aSearchByIdIsNeverHandedToTheSourceScanner() throws java.io.IOException {
        String job=source("src/main/java/baritone/gtnh/ReferenceProcessJob.java"),search=source("src/upstream/java/baritone/process/MineProcess.java");
        assertEquals("the one place the job starts the source search with a bare block",1,job.split("getToBlock\\(block\\)",-1).length-1);
        assertTrue(job.contains("if(scan==null&&loaded==null)engine.getGetToBlockProcess().getToBlock(block);"));
        int supplied=search.indexOf("if(supplied!=null)"),sweep=search.indexOf("scanChunkRadius(");
        assertTrue("locations an observation supplies are returned before the scanner is reached",supplied>=0&&supplied<sweep);
    }

    private static MiningObservation mining(int radius,String id){
        return new MiningObservation(world,WorkSpec.bounds(Map.of("min",List.of(-radius,48,-radius),"max",List.of(radius,80,radius))),List.of(Map.<String,Object>of("id",id)),List.of(),()->FEET);
    }
    private static double pass(double limitMs,int radius,String id){return worstMs(limitMs,()->{var scan=mining(radius,id);return ()->{scan.tick();return scan.passes==0;};});}
    private static long cells(int radius){return (2L*radius+1)*33*(2*radius+1);}
    @Test public void aScanOfMiningBoundsIsASliceATickToItsLastTick(){
        for(int radius:new int[]{24,64})check("scan of mining bounds, worst tick of the first pass, "+cells(radius)+" cells, none match",pass(LIMIT_MS,radius,"minecraft:bookshelf"));
        // A pass ends by publishing its matches and choosing the nearest, in the tick that reads the last cell. Found
        // 2026-10-05 at 0.2 s for 35,937 cells and 6 s for 79,233, when the matches were copied to publish them.
        for(int radius:new int[]{8,16,24,64})check("scan of mining bounds, worst tick of the first pass, "+cells(radius)+" cells, nearly all match",pass(LIMIT_MS,radius,"minecraft:stone"));
    }
    @Test public void aPassPublishesWhatItFoundAndTheNextStartsEmpty(){
        var scan=mining(2,"minecraft:stone");while(scan.passes==0)scan.tick();
        assertEquals(5*5*32,scan.matches());var first=scan.capture();
        var stone=new IBlockState(Blocks.stone,0,null,1,60,1);assertTrue(scan.has(stone)&&first.test(stone));
        // What a search captured is the pass it was captured in, whatever later passes find.
        world.set(1,60,1,Blocks.dirt);
        try{while(scan.passes==1)scan.tick();assertEquals(5*5*32-1,scan.matches());assertFalse(scan.has(stone));assertTrue(first.test(stone));}
        finally{world.set(1,60,1,Blocks.stone);}
        assertEquals(List.of(new BlockPos(8,64,8),new BlockPos(9,64,8),new BlockPos(8,66,8)),MiningObservation.nearest(List.of(new BlockPos(9,64,8),new BlockPos(20,64,8),new BlockPos(8,66,8),new BlockPos(8,64,8),new BlockPos(8,70,8)),FEET,3));
    }

    @Test public void theShapeWarmUpStaysInItsSlice(){
        int[] centre={0};
        check("block shapes, worst tick of a warm-up",worstMs(()->{BlockShapes.warm(world,centre[0]+=8,64,0);int[] ticks={40};return ()->{BlockShapes.answer();return ticks[0]-->0;};}));
    }

    @Test public void aFullQueueOfToolAndPickAsksIsAnsweredASliceATick() throws Exception {
        var tools=MiningTools.game;var picks=BlockIdentity.game;var pool=Executors.newSingleThreadExecutor();
        // The game's answers cost what a block's own hooks cost; here they cost nothing, so this holds the queue's own work.
        MiningTools.game=(stack,block,meta,x,y,z,placed,queued)->new Answer(1,true);BlockIdentity.game=(block,x,y,z)->Optional.empty();
        ItemStack[] stacks={null,new ItemStack(Items.iron_pickaxe),new ItemStack(Items.iron_shovel)};
        try{
            check("tool and pick-block asks, worst tick answering full queues",worstMs(()->{
                MiningTools.reset();MiningTools.answer(); // this thread is the game's; the search asks from another
                try{pool.submit(()->{for(Object o:Block.blockRegistry)for(int meta=0;meta<16;meta++){MiningTools.answers(stacks,(Block)o,meta,0,64,0,true);BlockIdentity.at(new IBlockState((Block)o,meta,null,0,64,0));}}).get(30,TimeUnit.SECONDS);}
                catch(Exception e){throw new AssertionError(e);}
                assertEquals(MiningTools.ASKED,MiningTools.pending());assertTrue(BlockIdentity.pending()>1000);
                return ()->{MiningTools.answer();return MiningTools.pending()+BlockIdentity.pending()>0;};
            }));
        }finally{pool.shutdownNow();MiningTools.game=tools;BlockIdentity.game=picks;MiningTools.reset();}
    }

    private static ClickWorld copy(int half){return new ClickWorld(world,new BlockPos(-half,64-half,-half),new BlockPos(half-1,63+half,half-1),false);}
    /**
     * 600 microseconds a tick is ClickRun.SLICE; 58 blocks a side is just under ClickWorld.MAX_VOLUME, where the map of
     * cells growing makes one tick about 5 ms. The tick that ends the copy hands it over as a ClickSpace: 5 s for 85,184
     * blocks and 37 s for 195,112 while that was a Map.copyOf (found 2026-10-05; the note in ClickSpace says why).
     */
    @Test public void theCopyAroundClicksIsMadeASliceATickAndHandedOverInOne(){
        for(int half:new int[]{8,22,29}){
            String n=8L*half*half*half+" blocks";
            check("copy around clicks, worst tick of "+n,worstMs(()->{var copy=copy(half);return ()->!copy.step(600_000L);}));
            var whole=copy(half);while(!whole.step(Long.MAX_VALUE)){}
            ClickSpace[] space={null};check("copy around clicks handed over, "+n,medianMs(()->()->space[0]=whole.space()));
            BlockPos[] all=new BlockPos[4096];for(int i=0;i<all.length;i++)all[i]=new BlockPos(i%16-8,64+i/256-8,i/16%16-8);
            check("4096 reads of a copy of "+n,medianMs(()->()->{for(BlockPos p:all)assertTrue(space[0].known(p));}));
        }
    }

    /** A cube half in the ground: cobblestone asked where stone and air are. */
    private static ConstructionPlan cube(int side){
        List<Map<String,Object>> cells=new ArrayList<>();
        for(int x=0;x<side;x++)for(int y=80-side/2;y<80+side/2;y++)for(int z=0;z<side;z++)cells.add(Map.of("pos",List.of(x,y,z),"id","minecraft:cobblestone"));
        return new ConstructionPlan(Map.of("cells",cells),new HashMap<>(),world);
    }
    /** A wall two thick, the most cells a plan holds: the shape whose positions hash (Vec3i) closest together. */
    private static ConstructionPlan wall(){
        List<Map<String,Object>> cells=new ArrayList<>();
        for(int x=0;x<64;x++)for(int y=80;y<112;y++)for(int z=0;z<2;z++)cells.add(Map.of("pos",List.of(x,y,z),"id","minecraft:cobblestone"));
        return new ConstructionPlan(Map.of("cells",cells),new HashMap<>(),world);
    }
    @Test public void aBuildReadsItsWholePlanEachTick(){
        for(var plan:List.of(cube(8),cube(16),wall())){
            // The world reads ReferenceConstructionProcess.survey makes for each cell, every tick; survey itself needs the job.
            check("build survey's reads of the plan, "+plan.cells.size()+" cells",medianMs(()->()->{
                int wrong=0;for(var c:plan.cells){var p=c.pos();if(plan.loaded(p)&&!plan.correct(c)&&(plan.occupied(p)||world.isAirBlock(p.getX(),p.getY(),p.getZ())))wrong++;}
                assertEquals(plan.cells.size(),wrong);}));
            // What startPass asks of the order when a step begins: what is shown, the air that stays, what waits. The
            // wall's copies (shown, pending, held) were 8 to 10 ms each as Map.copyOf and Set.copyOf.
            Set<BlockPos> toFill=new HashSet<>();for(var c:plan.cells)if(!plan.occupied(c.pos()))toFill.add(c.pos());
            BlockPos feet=new BlockPos(-2,80,0);
            check("build order at a pass start, "+plan.cells.size()+" cells",medianMs(()->()->{
                var shown=plan.steps.schematic(DeferredClearance.schematic(plan.schematic,Set.of(feet),false),plan.steps.count()-1);Set<BlockPos> now=new HashSet<>(toFill);now.retainAll(shown.keySet());
                var stays=BuildSteps.air(toFill,p->LegacyPlacement.empty(world,p.getX(),p.getY(),p.getZ()),feet,feet.up());
                BuildSteps.held(toFill,now,stays::contains);}));
        }
    }

    /**
     * The source builder asks ReferenceConstructionProcess for a goal for every unfinished cell near the player, every
     * tick, and a step of the player or a second passing makes them all old: 1,728 of them were made again in one tick,
     * up to 61 ms in the game. The goals themselves need a player (WorkAccess.Stands, the click prediction): here each
     * costs 729 reads of the world, asked as the adapter asks, with a step every fourth tick.
     */
    @Test public void aBuildersGoalsAreMadeASliceATick(){
        int cells=1728;int[] made=new int[cells];
        java.util.function.IntUnaryOperator goal=k->{
            int x=k%12,y=76+k/12%12,z=k/144,solid=0;made[k]++;
            for(int dx=-4;dx<=4;dx++)for(int dy=-4;dy<=4;dy++)for(int dz=-4;dz<=4;dz++)if(!world.isAirBlock(x+dx,y+dy,z+dz))solid++;
            return solid;
        };
        measure("build goals made in one tick, "+cells+" cells (what the limit is there to fail)",worstMs(Double.MAX_VALUE,()->()->{for(int k=0;k<cells;k++)goal.applyAsInt(k);return false;}));
        check("build goals, worst of 400 ticks, "+cells+" cells",worstMs(()->{
            var goals=new Sliced<Integer,Integer>();int[] tick={0};Arrays.fill(made,0);
            return ()->{
                if(tick[0]%4==0)goals.age();
                goals.tick(ReferenceConstructionProcess.GOAL_NS);
                for(int k=0;k<cells;k++){
                    if(goals.fresh(k)!=null)continue;
                    if(!goals.turn(k)){goals.last(k,-1);continue;}
                    long began=System.nanoTime();goals.put(k,goal.applyAsInt(k));goals.spent(System.nanoTime()-began);
                }
                return ++tick[0]<400;
            };
        }));
        assertTrue("every cell had its turn",Arrays.stream(made).min().getAsInt()>0);
    }

    private static ConstructionPlan row(int clicks){
        List<Map<String,Object>> cells=new ArrayList<>();
        for(int i=0;i<clicks;i++)cells.add(Map.of("pos",List.of(2*i,80,0),"id","minecraft:cobblestone","click",Map.of("face","up")));
        return new ConstructionPlan(Map.of("cells",cells),new HashMap<>(),world);
    }
    /** ClickRun.preview's own loop, repeated here because it reads the player's reach: the copy in one go, then the order. */
    private static void preview(ConstructionPlan plan){
        var body=new Vantages.Body(1.62,4.5);
        var copy=ClickWorld.around(world,plan.places.stream().map(StepPlan.Step::pos).toList(),7,false);while(!copy.step(Long.MAX_VALUE)){}
        ClickSpace w=copy.space();List<StepPlan.Step> open=new ArrayList<>(plan.places);Map<String,List<Vantages.Vantage>> ways=new HashMap<>();
        while(!open.isEmpty()){
            var pick=StepPlan.next(open,w,body,ways);assertTrue(pick.ready());
            open.remove(pick.step());w=w.with(pick.step().pos(),ClickSpace.Voxel.full(pick.step().pos(),pick.step().id(),false));StepPlan.placed(ways,open,pick.step().pos(),body);
        }
    }
    @Test public void aPreviewOfAFewClicksIsOneShortCall(){
        var plan=row(8);check("build preview, 8 click cells in a copy of "+StepPlan.volume(plan.places,7)+" blocks",medianMs(()->()->preview(plan)));
    }
    /**
     * FINDING 2026-10-05, not a budget: a preview copies and orders in the one call that asked, on the game thread, and a
     * row of 32 click cells (all a preview looks at, ClickRun.CHECKED) takes 11 ms: the copy's reads of the world, then
     * the order. A job does the same order on its search thread. (194 ms while the copy was handed over as a Map.copyOf;
     * the limit is four times what is measured now.)
     */
    private static final double PREVIEW_KNOWN_MS=50;
    @Test public void aPreviewOfThirtyTwoClicksIsNotSliced(){
        var plan=row(32);check("FINDING build preview, 32 click cells in a copy of "+StepPlan.volume(plan.places,7)+" blocks",PREVIEW_KNOWN_MS,worstMs(PREVIEW_KNOWN_MS,()->()->{preview(plan);return false;}));
    }
}
