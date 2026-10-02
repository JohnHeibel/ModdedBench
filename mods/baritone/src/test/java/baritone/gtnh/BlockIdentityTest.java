// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh;
import baritone.Baritone;
import baritone.ForgePlanningTestRunner;
import baritone.compat.IBlockState;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import org.junit.*;
import static org.junit.Assert.*;

/** Pick-block identity for the path search: never waited for, unanswered means avoided. */
@org.junit.runner.RunWith(ForgePlanningTestRunner.class)
public class BlockIdentityTest {
    private static BlockIdentity.Picker real;
    @BeforeClass public static void bootstrap(){
        cpw.mods.fml.common.Loader.injectData("7","99","40","1614","1.7.10","9.05",new java.io.File("."),List.of());
        net.minecraft.init.Bootstrap.func_151354_b();
        real=BlockIdentity.game;
    }
    @Before public void fresh(){MiningTools.reset();Baritone.settings().allSettings.forEach(s->s.reset());BlockRules.reset();}
    @After public void restore(){BlockIdentity.game=real;MiningTools.reset();Baritone.settings().allSettings.forEach(s->s.reset());BlockRules.reset();}
    private static <T> T search(Callable<T> work)throws Exception{
        var pool=Executors.newSingleThreadExecutor();
        try{return pool.submit(work).get(30,TimeUnit.SECONDS);}finally{pool.shutdownNow();}
    }
    // A chest has a tile entity, so its identity is per position, as a GregTech ore's is.
    private static IBlockState chest(int x){return new IBlockState(Blocks.chest,0,null,x,64,0);}
    private static final List<String> picks=new CopyOnWriteArrayList<>();
    private static void ores(){
        picks.clear();
        BlockIdentity.game=(b,x,y,z)->{picks.add(x+","+y+","+z);return x==1?Optional.of(new ItemStack(Blocks.stone,1,3)):x==2?Optional.of(new ItemStack(Blocks.stone,1,0)):x==3?null:Optional.empty();};
    }

    @Test public void aSearchMissIsQueuedNotWaitedForAndTheNextTickPicksIt()throws Exception {
        ores();MiningTools.answer(); // this thread ticks the game
        long[] took=new long[1];
        var first=search(()->{long t=System.nanoTime();var out=BlockIdentity.at(chest(1));took[0]=System.nanoTime()-t;return out;});
        assertNull(first);assertTrue("took "+took[0]/1000+" us",took[0]<20_000_000L);
        assertEquals(1,BlockIdentity.pending());assertTrue(picks.isEmpty());
        MiningTools.answer();
        assertEquals(List.of("1,64,0"),picks);assertEquals(0,BlockIdentity.pending());
        var second=search(()->BlockIdentity.at(chest(1)));
        assertTrue(second.isPresent());assertEquals(3,second.get().getItemDamage());
    }

    @Test public void theGameThreadPicksAtOnce() {
        ores();MiningTools.answer();
        assertEquals(3,BlockIdentity.at(chest(1)).get().getItemDamage());
        assertEquals(Optional.empty(),BlockIdentity.at(chest(4)));
        // No answer now (the block changed): nothing picked for the caller on the game thread, and nothing kept.
        assertEquals(Optional.empty(),BlockIdentity.at(chest(3)));
        assertEquals(Optional.empty(),BlockIdentity.at(chest(3)));
        assertEquals(List.of("1,64,0","4,64,0","3,64,0","3,64,0"),picks);
        assertEquals(0,BlockIdentity.pending());
    }

    @Test public void anUnpickedCellIsAHazardUntilTheGameSaysOtherwise()throws Exception {
        ores();MiningTools.answer();
        Baritone.settings().hazards.value=new ArrayList<>(List.of("item=minecraft:stone:3"));
        assertTrue(BlockRules.picksIdentity());
        assertEquals(List.of(true,true,true),search(()->List.of(BlockRules.hazardAt(chest(1)),BlockRules.hazardAt(chest(2)),BlockRules.hazardAt(chest(3)))));
        assertEquals(Map.of("item=not_picked_yet",3),BlockRules.applied().get("hazards"));
        for(int tick=0;tick<100&&BlockIdentity.pending()>0;tick++)MiningTools.answer(); // a tick picks what its budget allows
        // Picked: the stone:3 one is a hazard, the stone:0 one is not; the one with no answer stays avoided.
        assertEquals(List.of(true,false,true),search(()->List.of(BlockRules.hazardAt(chest(1)),BlockRules.hazardAt(chest(2)),BlockRules.hazardAt(chest(3)))));
        assertEquals(1,(int)BlockRules.applied().get("hazards").get("item=minecraft:stone:3"));
        // Without a pick-block rule nothing is asked at all.
        Baritone.settings().hazards.value=new ArrayList<>(List.of("minecraft:web"));
        assertFalse(BlockRules.picksIdentity());
        assertFalse(search(()->BlockRules.hazardAt(chest(9))));
        assertEquals(1,BlockIdentity.pending()); // only chest 3 is still waiting
    }
}
