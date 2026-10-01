// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh;
import baritone.ForgePlanningTestRunner;
import baritone.compat.IBlockState;
import baritone.gtnh.ReferenceToolPolicy.Answer;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import org.junit.*;
import static org.junit.Assert.*;

/** A paused world runs no client tick: its frames answer what the path search asked of the game thread instead. */
@org.junit.runner.RunWith(ForgePlanningTestRunner.class)
public class PausedServiceTest {
    private static MiningTools.Asker tools;
    private static BlockIdentity.Picker picks;
    @BeforeClass public static void bootstrap(){
        cpw.mods.fml.common.Loader.injectData("7","99","40","1614","1.7.10","9.05",new java.io.File("."),List.of());
        net.minecraft.init.Bootstrap.func_151354_b();
        tools=MiningTools.game;picks=BlockIdentity.game;
    }
    @Before public void fresh(){MiningTools.reset();}
    @After public void restore(){MiningTools.game=tools;BlockIdentity.game=picks;MiningTools.reset();}
    private static <T> T search(Callable<T> work)throws Exception{
        var pool=Executors.newSingleThreadExecutor();
        try{return pool.submit(work).get(30,TimeUnit.SECONDS);}finally{pool.shutdownNow();}
    }

    @Test public void pausedFramesAnswerTheSearchsQuestions()throws Exception{
        List<String> asked=new CopyOnWriteArrayList<>();
        MiningTools.game=(s,b,m,x,y,z,placed,queued)->{asked.add("tool "+x+(queued?" queued":""));return new Answer(.25,true);};
        BlockIdentity.game=(b,x,y,z)->{asked.add("pick "+x);return Optional.of(new ItemStack(Blocks.stone,1,x));};
        BaritoneNavigation.answerSearch(); // this thread is the game's
        // The search asks while the world is paused: nothing waits, everything is queued.
        search(()->{MiningTools.answers(new ItemStack[]{new ItemStack(Items.iron_pickaxe)},Blocks.stone,0,1,64,0,true);
            return BlockIdentity.at(new IBlockState(Blocks.chest,0,null,2,64,0));});
        assertEquals(1,MiningTools.pending());assertEquals(1,BlockIdentity.pending());assertTrue(asked.isEmpty());
        // Paused frames, the hook the clock runs while no tick does.
        for(int frame=0;frame<100&&(MiningTools.pending()>0||BlockIdentity.pending()>0);frame++)BaritoneNavigation.answerSearch();
        assertEquals(0,MiningTools.pending());assertEquals(0,BlockIdentity.pending());
        assertEquals(Set.of("tool 1 queued","pick 2"),new HashSet<>(asked));
        // The search's next look finds the game's answers.
        assertEquals(2,search(()->BlockIdentity.at(new IBlockState(Blocks.chest,0,null,2,64,0))).get().getItemDamage());
        assertEquals(new Answer(.25,true),search(()->MiningTools.answers(new ItemStack[]{new ItemStack(Items.iron_pickaxe)},Blocks.stone,0,1,64,0,true))[0]);
    }
}
