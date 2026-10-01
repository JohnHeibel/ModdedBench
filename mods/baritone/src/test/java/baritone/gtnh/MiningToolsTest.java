// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh;
import baritone.ForgePlanningTestRunner;
import baritone.gtnh.ReferenceToolPolicy.Answer;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import org.junit.*;
import static org.junit.Assert.*;

@org.junit.runner.RunWith(ForgePlanningTestRunner.class)
public class MiningToolsTest {
    private static MiningTools.Asker real;
    @BeforeClass public static void bootstrap(){
        cpw.mods.fml.common.Loader.injectData("7","99","40","1614","1.7.10","9.05",new java.io.File("."),List.of());
        net.minecraft.init.Bootstrap.func_151354_b();
        real=MiningTools.game;
    }
    @Before public void fresh(){MiningTools.reset();}
    @After public void restore(){MiningTools.game=real;MiningTools.reset();}
    /** The path search's side: another thread than the one that ticks the game. */
    private static <T> T search(Callable<T> work)throws Exception{
        var pool=Executors.newSingleThreadExecutor();
        try{return pool.submit(work).get(30,TimeUnit.SECONDS);}finally{pool.shutdownNow();}
    }
    private static ItemStack pick(){return new ItemStack(Items.iron_pickaxe);}

    @Test public void instantBreakStrengthIsOneTick() {
        assertEquals(1,MiningTools.breakTicks(Double.POSITIVE_INFINITY),0);
        assertEquals(1,MiningTools.breakTicks(1),0);
        assertEquals(4,MiningTools.breakTicks(.25),0);
    }
    @Test public void invalidAndUnbreakableStrengthsStayRejected() {
        for(double strength:new double[]{Double.NaN,Double.NEGATIVE_INFINITY,-1,0})
            assertEquals(Double.POSITIVE_INFINITY,MiningTools.breakTicks(strength),0);
    }
    @Test public void onePickPrefersHarvestThenSpeedThenTheHeldSlot() {
        var slow=new ReferenceToolPolicy.Answer(.1,true);var fast=new ReferenceToolPolicy.Answer(.5,false);var none=new ReferenceToolPolicy.Answer(0,true);
        var answers=new ReferenceToolPolicy.Answer[]{fast,slow,slow,none,null};boolean[] all={true,true,true,true,true};
        assertEquals(1,MiningTools.pick(answers,all,MiningTools.order(0,5)));
        assertEquals(2,MiningTools.pick(answers,all,MiningTools.order(2,5)));
        assertEquals(0,MiningTools.pick(answers,new boolean[]{true,false,false,true,true},MiningTools.order(0,5)));
        assertEquals(-1,MiningTools.pick(new ReferenceToolPolicy.Answer[]{none,null},new boolean[]{true,true},MiningTools.order(0,2)));
    }

    @Test public void aSearchMissReturnsAtOnceProvisionallyAndTheNextTickMakesItPrecise()throws Exception {
        List<String> asks=new CopyOnWriteArrayList<>();
        MiningTools.game=(s,b,m,x,y,z,placed,queued)->{asks.add((s==null?"hand":"pick")+(queued?" queued":""));return s==null?new Answer(1/150.,false):new Answer(.25,true);};
        MiningTools.answer(); // this thread ticks the game
        ItemStack[] stacks={pick(),null};
        long[] took=new long[1];
        var first=search(()->{long t=System.nanoTime();var out=MiningTools.answers(stacks,Blocks.stone,0,1,2,3,true);took[0]=System.nanoTime()-t;return out;});
        assertTrue("took "+took[0]/1000+" us",took[0]<20_000_000L); // the old wait was up to 200 ms
        // Nothing measured yet: a bare hand without harvest at stone's hardness (1.5), for every stack.
        assertEquals(new Answer(1/150.,false),first[0]);assertEquals(first[0],first[1]);
        assertTrue(asks.isEmpty());assertEquals(2,MiningTools.pending());
        MiningTools.answer();
        assertEquals(Set.of("pick queued","hand queued"),new HashSet<>(asks));assertEquals(0,MiningTools.pending());
        var second=search(()->MiningTools.answers(stacks,Blocks.stone,0,1,2,3,true));
        assertEquals(new Answer(.25,true),second[0]);assertEquals(new Answer(1/150.,false),second[1]);
        assertEquals(2,asks.size());
    }

    @Test public void theGameThreadAsksDirectlyOnceAndKeepsTheAnswer() {
        int[] asks={0};
        MiningTools.game=(s,b,m,x,y,z,placed,queued)->{asks[0]++;assertFalse(queued);return new Answer(.5,true);};
        MiningTools.answer();
        assertEquals(new Answer(.5,true),MiningTools.answers(new ItemStack[]{pick()},Blocks.stone,0,0,0,0,true)[0]);
        assertEquals(new Answer(.5,true),MiningTools.answers(new ItemStack[]{pick()},Blocks.stone,0,9,9,9,true)[0]); // per state, not position
        assertEquals(1,asks[0]);assertEquals(0,MiningTools.pending());
    }

    @Test public void perPositionAnswersFallBackToTheirKindsWeakest()throws Exception {
        assertTrue(Blocks.chest.hasTileEntity(0));
        MiningTools.game=(s,b,m,x,y,z,placed,queued)->x==0?new Answer(.1,true):new Answer(.2,false);
        MiningTools.answer();
        MiningTools.answers(new ItemStack[]{pick()},Blocks.chest,0,0,64,0,true);
        MiningTools.answers(new ItemStack[]{pick()},Blocks.chest,0,5,64,0,true);
        var worn=pick();worn.setItemDamage(40);
        var far=search(()->MiningTools.answers(new ItemStack[]{pick(),worn,new ItemStack(Items.apple)},Blocks.chest,0,9,64,9,true));
        // The slowest and least harvesting seen for this tool kind on this block, whatever the durability.
        assertEquals(new Answer(.1,false),far[0]);assertEquals(far[0],far[1]);
        // No answer for an apple yet: a bare hand at the chest's hardness (2.5).
        assertEquals(new Answer(1/250.,false),far[2]);
        // A measured position keeps its own answer.
        assertEquals(new Answer(.1,true),search(()->MiningTools.answers(new ItemStack[]{pick()},Blocks.chest,0,0,64,0,true))[0]);
        assertEquals(new Answer(.2,false),search(()->MiningTools.answers(new ItemStack[]{pick()},Blocks.chest,0,5,64,0,true))[0]);
    }

    @Test public void aBigSearchEvictsTheOldestAnswersNotAllOfThem()throws Exception {
        MiningTools.game=(s,b,m,x,y,z,placed,queued)->new Answer(1+x,true);
        MiningTools.answer();
        ItemStack[] stacks={pick()};int n=200_000;
        for(int x=0;x<n;x++)MiningTools.answers(stacks,Blocks.chest,0,x,64,0,true);
        int kept=search(()->{int k=0;for(int x=n-1000;x<n;x++)if(MiningTools.answers(stacks,Blocks.chest,0,x,64,0,true)[0].strength()==1+x)k++;return k;});
        assertTrue("kept "+kept+" of the last 1000",kept>=950);
        int old=search(()->{int k=0;for(int x=0;x<1000;x++)if(MiningTools.answers(stacks,Blocks.chest,0,x,64,0,true)[0].strength()==1+x)k++;return k;});
        assertTrue("kept "+old+" of the first 1000",old<100);
    }

    @Test public void aTicksAnswersStayWithinTheBudget()throws Exception {
        MiningTools.game=(s,b,m,x,y,z,placed,queued)->{long until=System.nanoTime()+20_000;while(System.nanoTime()<until)Thread.onSpinWait();return new Answer(1,true);};
        MiningTools.answer();
        search(()->{for(int x=0;x<10_000;x++)MiningTools.answers(new ItemStack[]{pick()},Blocks.chest,0,x,64,0,true);return null;});
        assertEquals(MiningTools.ASKED,MiningTools.pending());
        long t=System.nanoTime();MiningTools.answer();long took=System.nanoTime()-t;
        // 4096 asks of 20 us would be 80 ms; a tick answers a few and leaves the rest for the next.
        assertTrue("took "+took/1000+" us",took<MiningTools.TICK_BUDGET_NS+5_000_000L);
        assertTrue(MiningTools.pending()>MiningTools.ASKED-100);
    }

    @Test public void searchTimePerTileEntityCellIsNotWaiting()throws Exception {
        // A synthetic underground search: every cell a tile-entity block (a GregTech ore keeps its material in one), nine
        // stacks, the game ticking every 50 ms and taking 10 us an answer. The old wait made this ~25 ms a cell.
        MiningTools.game=(s,b,m,x,y,z,placed,queued)->{long until=System.nanoTime()+10_000;while(System.nanoTime()<until)Thread.onSpinWait();return new Answer(.1,true);};
        var ticking=new CountDownLatch(1);var stop=new java.util.concurrent.atomic.AtomicBoolean();
        var game=new Thread(()->{while(!stop.get()){MiningTools.answer();ticking.countDown();try{Thread.sleep(50);}catch(InterruptedException e){return;}}});
        game.start();
        try{
            assertTrue(ticking.await(10,TimeUnit.SECONDS));
            ItemStack[] stacks=new ItemStack[9];stacks[0]=pick();stacks[1]=new ItemStack(Items.stone_pickaxe);stacks[2]=new ItemStack(Items.apple);stacks[3]=new ItemStack(Blocks.cobblestone,64);
            int cells=20_000;long t=System.nanoTime();
            for(int i=0;i<cells;i++)MiningTools.answers(stacks,Blocks.chest,0,i%100,20+i/10_000,i/100%100,true);
            double us=(System.nanoTime()-t)/1000.0/cells;
            System.out.printf("MiningTools.answers: %.2f us per tile-entity cell, 9 stacks, %d cells%n",us,cells);
            assertTrue(us+" us a cell",us<200);
        }finally{stop.set(true);game.interrupt();game.join(5000);}
    }
}
