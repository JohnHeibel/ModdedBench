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
        // A top under 0.875 puts the player's feet cell, floor(minY+0.1251), inside the block: not a floor to plan on.
        assertFalse(shape(new double[]{0,0,0,1,.8125,1}).standable());
    }
    @Test public void aTopInsideTheCellMovesTheFeetUpLikeASlab(){
        var slab=shape(new double[]{0,0,0,1,.5,1});
        assertTrue(slab.floorInside(.5));assertFalse(slab.floorInside(0));   // on it, or beside it under its top
        assertTrue(shape(new double[]{0,0,0,1,.75,1}).floorInside(.75));      // an enchanting table, by its shape
        assertFalse(shape(new double[]{0,0,0,1,.875,1}).floorInside(.875));   // soul sand: the feet are already above
        assertFalse(shape(new double[]{0,0,0,1,.0625,1}).floorInside(.0625)); // a carpet is walked through
        assertFalse(shape().floorInside(1));
    }
    @Test public void theWarmUpRingsCoverTheSquareOnceNearestFirst(){
        Set<List<Integer>> seen=new HashSet<>();
        for(int r=0;r<=16;r++)for(int k=0;k<(r==0?1:8*r);k++){
            int x=BlockShapes.ringX(r,k),z=BlockShapes.ringZ(r,k);
            assertEquals(r,Math.max(Math.abs(x),Math.abs(z)));assertTrue(seen.add(List.of(x,z)));
        }
        assertEquals(33*33,seen.size());
    }
    @Test public void aStandableTopKeepsItsSurfaceForFootprintChecks(){
        var cube=shape(new double[]{0,0,0,1,1,1});
        assertTrue(cube.full());assertTrue(cube.supports(1.25,-.25));assertFalse(cube.supports(1.31,.5));
        // A floor of thin vertical pipe: holds a centred player, not one at the corner a diagonal crosses.
        var pipe=shape(new double[]{.375,0,.375,.625,1,.625});
        assertTrue(pipe.standable());assertFalse(pipe.full());
        assertTrue(pipe.supports(.5,.5));assertTrue(pipe.supports(.9,.5));assertFalse(pipe.supports(1,1));assertFalse(pipe.supports(.95,.5));
        // Only boxes at the top count: a stair's low step does not hold a player at the far edge.
        var stairs=shape(new double[]{0,0,0,1,.5,1},new double[]{.5,.5,0,1,1,1});
        assertFalse(stairs.full());assertTrue(stairs.supports(.5,.5));assertFalse(stairs.supports(.1,.5));
    }
    @Test public void anyItemGainKeysAStackByIdMetaAndNbtButAToolByIdAndMeta(){
        var pick=new net.minecraft.item.ItemStack(net.minecraft.init.Items.iron_pickaxe);var worn=pick.copy();worn.setTagCompound(new net.minecraft.nbt.NBTTagCompound());worn.getTagCompound().setInteger("wear",3);
        assertEquals(MiningProcess.identity(pick),MiningProcess.identity(worn));
        var swung=pick.copy();swung.setItemDamage(5);assertEquals(MiningProcess.identity(pick),MiningProcess.identity(swung));
        var stone=new net.minecraft.item.ItemStack(Blocks.stone);var tagged=stone.copy();tagged.setTagCompound(worn.getTagCompound());
        assertNotEquals(MiningProcess.identity(stone),MiningProcess.identity(tagged));
    }
}
