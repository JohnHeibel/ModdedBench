// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh;

import baritone.Baritone;
import baritone.ForgePlanningTestRunner;
import baritone.compat.IBlockState;
import net.minecraft.init.Blocks;
import org.junit.*;
import java.util.*;
import static org.junit.Assert.*;

/** The model-owned block lists and the collision-shape verdicts, without a running client. */
@org.junit.runner.RunWith(ForgePlanningTestRunner.class)
public class BlockRulesTest {
    @BeforeClass public static void bootstrap(){
        cpw.mods.fml.common.Loader.injectData("7","99","40","1614","1.7.10","9.05",new java.io.File("."),List.of());
        net.minecraft.init.Bootstrap.func_151354_b();
    }
    @Before public void defaults(){Baritone.settings().allSettings.forEach(s->s.reset());BlockRules.reset();}

    @Test public void hazardsAreTheModelsListAndADefaultCanBeRemoved(){
        assertTrue(BlockRules.hazard(IBlockState.of(Blocks.web,0)));
        Baritone.settings().hazards.value=new ArrayList<>(List.of("minecraft:wool:14"));
        assertFalse(BlockRules.hazard(IBlockState.of(Blocks.web,0)));
        assertTrue(BlockRules.hazard(IBlockState.of(Blocks.wool,14)));
        assertFalse(BlockRules.hazard(IBlockState.of(Blocks.wool,13)));
        assertEquals(Map.of("hazards",Map.of("minecraft:web:0",1,"minecraft:wool:14",1)),BlockRules.applied());
    }
    @Test public void neverStandOnWinsOverStandOnAndHazardsRefuseStanding(){
        var stone=IBlockState.of(Blocks.stone,0);
        assertNull(BlockRules.standOn(stone));
        Baritone.settings().standOn.value=new ArrayList<>(List.of("minecraft:stone"));
        assertEquals(Boolean.TRUE,BlockRules.standOn(stone));
        Baritone.settings().neverStandOn.value=new ArrayList<>(List.of("minecraft:stone:0"));
        assertEquals(Boolean.FALSE,BlockRules.standOn(stone));
        assertEquals(Boolean.FALSE,BlockRules.standOn(IBlockState.of(Blocks.cactus,0)));
    }
    @Test public void unknownOrMalformedEntriesAreRefusedWhenSet(){
        assertThrows(IllegalArgumentException.class,()->BlockRules.validate("hazards",List.of("nomod:quicksand")));
        BlockRules.validate("hazards",List.of("item=minecraft:stone:3","item=minecraft:dye"));
        assertThrows(IllegalArgumentException.class,()->BlockRules.validate("hazards",List.of("item=nomod:thing")));
        assertThrows(IllegalArgumentException.class,()->BlockRules.validate("standon",List.of("item=minecraft:stone")));
        assertThrows(IllegalArgumentException.class,()->BlockRules.validate("standon",List.of("minecraft:stone:16")));
        BlockRules.validate("neverstandon",List.of("minecraft:stone:3","minecraft:dirt"));
    }
    @Test public void iceAndSilverfishStoneAreOnlyDefaults(){
        assertTrue(BlockRules.neverBreak(IBlockState.of(Blocks.ice,0)));
        assertTrue(BlockRules.neverBreak(IBlockState.of(Blocks.monster_egg,2)));
        Baritone.settings().blocksToDisallowBreaking.value=new ArrayList<>();
        assertFalse(BlockRules.neverBreak(IBlockState.of(Blocks.ice,0)));
    }
    private static BlockShapes.Shape shape(double[]... boxes){return BlockShapes.classify(List.of(boxes));}
    @Test public void collisionBoxesDecideStandingAndPassing(){
        assertTrue(shape().empty());assertFalse(shape().standable());
        assertTrue(shape(new double[]{0,0,0,1,1,1}).standable());
        assertTrue(shape(new double[]{.0625,0,.0625,.9375,.875,.9375}).standable());               // a chest
        assertTrue(shape(new double[]{0,0,0,1,.5,1},new double[]{.5,.5,0,1,1,1}).standable());   // stairs
        assertFalse(shape(new double[]{0,0,0,1,.5,1}).standable());                               // a low slab-like block
        assertFalse(shape(new double[]{.375,0,.375,.625,1.5,.625}).standable());                  // a fence post
        assertFalse(shape(new double[]{0,0,0,1,.3125,1},new double[]{0,0,0,.125,1,1}).standable()); // a cauldron's rim
        assertFalse(shape(new double[]{0,0,0,1,.1875,1}).empty());                                // a closed trapdoor
    }
    @Test public void anyItemGainKeysAStackByIdMetaAndNbtButAToolByIdAndMeta(){
        var pick=new net.minecraft.item.ItemStack(net.minecraft.init.Items.iron_pickaxe);var worn=pick.copy();worn.setTagCompound(new net.minecraft.nbt.NBTTagCompound());worn.getTagCompound().setInteger("wear",3);
        assertEquals(MiningProcess.identity(pick),MiningProcess.identity(worn));
        var stone=new net.minecraft.item.ItemStack(Blocks.stone);var tagged=stone.copy();tagged.setTagCompound(worn.getTagCompound());
        assertNotEquals(MiningProcess.identity(stone),MiningProcess.identity(tagged));
    }
}
