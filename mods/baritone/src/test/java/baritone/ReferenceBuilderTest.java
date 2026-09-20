// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone;

import baritone.api.schematic.*;
import baritone.api.utils.*;
import baritone.compat.*;
import baritone.process.BuilderProcess;
import baritone.process.FollowProcess;
import baritone.selection.Selection;
import org.junit.*;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.Assert.*;

@org.junit.runner.RunWith(ForgePlanningTestRunner.class)
public class ReferenceBuilderTest {
    @BeforeClass public static void bootstrap(){
        cpw.mods.fml.common.Loader.injectData("7","99","40","1614","1.7.10","9.05",new java.io.File("."),List.of());
        net.minecraft.init.Bootstrap.func_151354_b();
    }
    @Before public void defaults(){Baritone.settings().allSettings.forEach(s->s.reset());}
    @Test public void nativePlacementGoalRetainsSourceGeometryAndHeuristic(){
        var source=new BuilderProcess.GoalAdjacent(new BlockPos(3,70,4),new BlockPos(3,69,4),false);
        var goal=new NativePlacementGoal(source,Set.of(new BlockPos(3,70,3),new BlockPos(3,69,4)));
        assertTrue(goal.isInGoal(3,70,3));assertFalse(goal.isInGoal(2,70,4));
        assertFalse(goal.isInGoal(3,69,4));assertEquals(source.heuristic(0,65,0),goal.heuristic(0,65,0),0);
    }
    @Test public void futureBuildFeetAndPlayerFeetShareSlabConvention(){
        java.util.function.Predicate<BlockPos> lowerSlab=p->p.getY()==64;
        assertEquals(new BetterBlockPos(-2,65,-4),NavigationCoordinates.feet(-1.5,64.5,-3.5,lowerSlab));
        assertEquals(new BetterBlockPos(-2,65,-4),NavigationCoordinates.feet(-1.5,65,-3.5,lowerSlab));
        assertEquals(new BetterBlockPos(-2,64,-4),NavigationCoordinates.feet(-1.5,64,-3.5,p->false));
    }
    @Test public void loadedGoalAdaptsToSlabAndRetainsKnowledgeAcrossUnload(){
        var physical=new BlockPos(-700,64,500);
        var unknown=NavigationCoordinates.goal(physical,physical,null);assertEquals(physical,unknown);
        var lower=IBlockState.of(net.minecraft.init.Blocks.stone_slab,0);
        var loaded=NavigationCoordinates.goal(physical,unknown,lower);assertEquals(physical.up(),loaded);
        assertEquals(loaded,NavigationCoordinates.goal(physical,loaded,null));
        assertEquals(physical,NavigationCoordinates.goal(physical,loaded,IBlockState.of(net.minecraft.init.Blocks.stone_slab,8)));
        assertEquals(physical,NavigationCoordinates.goal(physical,loaded,IBlockState.of(net.minecraft.init.Blocks.air,0)));
    }
    @Test public void sneakPredictionDoesNotCountResidualNativeCameraOffsetTwice() throws Exception {
        var player=allocate(net.minecraft.client.entity.EntityPlayerSP.class);
        player.movementInput=new net.minecraft.util.MovementInput();
        player.posX=-130.75;player.posZ=358.5;player.yOffset=1.62F;
        player.movementInput.sneak=true;
        player.ySize=0.080000006F;player.posY=176.0+player.yOffset-player.ySize;
        var settled=LegacyPlayer.eyes(player);
        assertEquals(settled,LegacyPlayer.sneakingEyes(player));
        player.movementInput.sneak=false;
        for(float residual:new float[]{0.080000006F,0.032000003F,0.012800001F,0}){
            player.ySize=residual;player.posY=176.0+player.yOffset-residual;
            var predicted=LegacyPlayer.sneakingEyes(player);
            assertEquals(settled.x,predicted.x,0);assertEquals(settled.y,predicted.y,1e-10);assertEquals(settled.z,predicted.z,0);
        }
    }
    @Test public void projectedSneakIsVisibleToNativeQueriesAndAlwaysRestored() throws Exception {
        var player=allocate(net.minecraft.client.entity.EntityPlayerSP.class);
        player.movementInput=new net.minecraft.util.MovementInput();
        assertFalse(player.isSneaking());
        assertTrue(LegacyPlayer.withSneakingPose(player,()->{
            assertTrue(player.isSneaking());
            assertTrue(LegacyPlayer.withSneakingPose(player,player::isSneaking));
            return player.isSneaking();
        }));
        assertFalse(player.movementInput.sneak);
        try {LegacyPlayer.withSneakingPose(player,()->{throw new IllegalStateException("native query failed");});fail();}
        catch(IllegalStateException expected){assertEquals("native query failed",expected.getMessage());}
        assertFalse(player.movementInput.sneak);
    }
    @Test public void workerMaterialIdentityPreservesMetadataAndSnapshotsNbt(){
        var stack=new net.minecraft.item.ItemStack(net.minecraft.init.Blocks.wool,8,4);
        var tag=new net.minecraft.nbt.NBTTagCompound();tag.setInteger("machineVariant",42);stack.setTagCompound(tag);
        var captured=StackIdentity.capture(stack);var same=stack.copy();same.stackSize=1;
        assertEquals(captured,StackIdentity.capture(same));
        stack.getTagCompound().setInteger("machineVariant",43);
        assertNotEquals(captured,StackIdentity.capture(stack));
        same.setItemDamage(5);assertNotEquals(captured,StackIdentity.capture(same));
    }
    @Test public void exactMaterialAvailabilityDoesNotRelaxActualPlacementState() throws Exception {
        var stack=new net.minecraft.item.ItemStack(net.minecraft.init.Blocks.stone_slab,1,0);
        var expected=StackIdentity.capture(stack);
        var desired=IBlockState.of(net.minecraft.init.Blocks.stone_slab,8);
        assertTrue(LegacyStateProperties.hasOrientation(desired.getBlock()));
        assertFalse(LegacyStateProperties.hasOrientation(net.minecraft.init.Blocks.double_stone_slab));
        assertFalse(LegacyStateProperties.same(IBlockState.of(desired.getBlock(),0),desired,true,List.of()));
        var lower=IBlockState.of(net.minecraft.init.Blocks.stone_slab,0).withPlacementItem(stack);
        var upper=desired.withPlacementItem(stack);
        var wrongStack=stack.copy();wrongStack.setItemDamage(1);
        var wrongVariant=IBlockState.of(net.minecraft.init.Blocks.stone_slab,1).withPlacementItem(wrongStack).asApproximateMaterial();
        var method=BuilderProcess.class.getDeclaredMethod("valid",IBlockState.class,IBlockState.class,boolean.class,
            BuilderProcess.StateValidator.class,java.util.function.BiPredicate.class,java.util.function.Predicate.class,java.util.function.BiPredicate.class);
        method.setAccessible(true);var builder=allocate(BuilderProcess.class);
        BuilderProcess.StateValidator validator=(current,wanted,item)->expected.equals(current.placementIdentity());
        java.util.function.BiPredicate<IBlockState,IBlockState> material=(current,wanted)->current.getBlock()==wanted.getBlock();
        java.util.function.Predicate<IBlockState> deferred=state->false;
        assertEquals(true,method.invoke(builder,lower.withPlacementItem(stack).asApproximateMaterial(),desired,true,validator,null,deferred,material));
        assertEquals(false,method.invoke(builder,wrongVariant,desired,true,validator,null,deferred,material));
        assertEquals(false,method.invoke(builder,lower,desired,true,validator,null,deferred,material));
        assertEquals(true,method.invoke(builder,upper,desired,true,validator,null,deferred,material));
    }
    @Test public void sourcePlacementGoalsExcludeTheBlockAndItsSupport(){
        var goal=new BuilderProcess.GoalAdjacent(new BlockPos(-7,65,8),new BlockPos(-7,64,8),false);
        assertFalse(goal.isInGoal(-7,65,8));assertFalse(goal.isInGoal(-7,64,8));
        assertFalse(goal.isInGoal(-6,64,8));assertTrue(goal.isInGoal(-6,65,8));
        var pillar=new BuilderProcess.GoalPlace(new BlockPos(-7,65,8));
        assertTrue(pillar.isInGoal(-7,66,8));assertFalse(pillar.isInGoal(-7,65,8));
    }
    @Test public void temporarySupportPolicyPreservesConfiguredItemAndExactNativeFilter() throws Exception {
        var inventory=allocate(baritone.behavior.InventoryBehavior.class);
        var stack=new net.minecraft.item.ItemStack(net.minecraft.init.Blocks.wool,4,2);
        var tag=new net.minecraft.nbt.NBTTagCompound();tag.setString("purpose","support");stack.setTagCompound(tag);
        var identity=StackIdentity.capture(stack);
        Baritone.settings().acceptableThrowawayItems.value=List.of(stack.getItem());
        inventory.throwawayFilter=item->identity.equals(StackIdentity.capture(item));
        assertTrue(inventory.isGenericThrowaway(stack));
        var wrong=stack.copy();wrong.setItemDamage(3);assertFalse(inventory.isGenericThrowaway(wrong));
        wrong=stack.copy();wrong.getTagCompound().setString("purpose","keep");assertFalse(inventory.isGenericThrowaway(wrong));
        wrong=stack.copy();wrong.stackSize=0;assertFalse(inventory.isGenericThrowaway(wrong));
        assertFalse(inventory.isGenericThrowaway(null));
        Baritone.settings().acceptableThrowawayItems.value=List.of();assertFalse(inventory.isGenericThrowaway(stack));
    }
    @Test public void sourceBreakGoalDoesNotStandOnUnsupportedTarget(){
        var goal=new BuilderProcess.GoalBreak(new BlockPos(0,64,0));
        assertTrue(goal.isInGoal(1,64,0));assertFalse(goal.isInGoal(0,65,0));
    }
    @Test public void selectionOperationsPreserveInclusiveNegativeBounds(){
        var selection=new Selection(new BetterBlockPos(-4,63,-8),new BetterBlockPos(-1,65,-6));
        var expanded=selection.expand(EnumFacing.WEST,2);
        assertEquals(-6,expanded.min().x);assertEquals(-1,expanded.max().x);
        assertEquals(selection.min(),expanded.contract(EnumFacing.EAST,2).min());
        assertEquals(new BetterBlockPos(-4,60,-8),selection.shift(EnumFacing.DOWN,3).min());
        assertEquals(0,selection.aabb().maxX,0);assertEquals(66,selection.aabb().maxY,0);
    }
    @Test public void fillSchematicPreservesExactMetadataAndCompatibleExistingState(){
        var fill=new FillSchematic(3,2,1,new BlockOptionalMeta(net.minecraft.init.Blocks.planks,2));
        var wanted=IBlockState.of(net.minecraft.init.Blocks.planks,2);var other=IBlockState.of(net.minecraft.init.Blocks.planks,1);
        assertSame(wanted,fill.desiredState(0,0,0,wanted,List.of(other)));
        assertEquals(wanted,fill.desiredState(0,0,0,other,List.of(other,wanted)));
        assertEquals(wanted,fill.desiredState(0,0,0,other,List.of(other)));
    }
    @Test public void ignoredOrientationPreservesBlockVariantMetadata(){
        var oakVertical=IBlockState.of(net.minecraft.init.Blocks.log,0);
        var oakHorizontal=IBlockState.of(net.minecraft.init.Blocks.log,4);
        var spruceHorizontal=IBlockState.of(net.minecraft.init.Blocks.log,5);
        assertTrue(LegacyStateProperties.same(oakVertical,oakHorizontal,true,List.of()));
        assertFalse(LegacyStateProperties.same(oakVertical,spruceHorizontal,true,List.of()));
        assertFalse(LegacyStateProperties.same(IBlockState.of(Blocks.STONE,1),IBlockState.of(Blocks.STONE,2),true,List.of("facing")));
    }
    @Test public void sourceSettingsRoundTripNativeRegistriesAndEmptyCollections(){
        var settings=Baritone.settings();
        SettingsUtil.parseAndApply(settings,"acceptablethrowawayitems","minecraft:cobblestone,minecraft:dirt");
        assertEquals(2,settings.acceptableThrowawayItems.value.size());
        assertEquals("minecraft:cobblestone,minecraft:dirt",SettingsUtil.settingValueToString(settings.acceptableThrowawayItems));
        SettingsUtil.parseAndApply(settings,"buildvalidsubstitutes","minecraft:stone->minecraft:cobblestone,minecraft:dirt");
        assertEquals(2,settings.buildValidSubstitutes.value.get(Blocks.STONE).size());
        SettingsUtil.parseAndApply(settings,"buildvalidsubstitutes","");assertTrue(settings.buildValidSubstitutes.value.isEmpty());
        SettingsUtil.parseAndApply(settings,"acceptablethrowawayitems","");assertTrue(settings.acceptableThrowawayItems.value.isEmpty());
        assertThrows(IllegalArgumentException.class,()->SettingsUtil.parseAndApply(settings,"acceptablethrowawayitems","absent:invalid"));
    }
    @Test public void sourceFollowUsesNativeEntityFeetOffsetAndRadius() throws Exception {
        // Allocate the actual native EntityItem without a World: towards only
        // reads inherited position and bounding-box fields. This keeps the test
        // focused on source FollowProcess geometry rather than a live client.
        var drop=allocate(net.minecraft.entity.item.EntityItem.class);
        drop.posX=10.9;drop.posZ=-3.1;
        setObject(drop,net.minecraft.entity.Entity.class.getField("boundingBox"),
            net.minecraft.util.AxisAlignedBB.getBoundingBox(10.4,64.25,-3.6,11.4,65.25,-2.6));
        var process=allocate(FollowProcess.class);
        Method towards=FollowProcess.class.getDeclaredMethod("towards",net.minecraft.entity.Entity.class);
        towards.setAccessible(true);
        var settings=Baritone.settings();
        settings.followRadius.value=2;settings.followOffsetDistance.value=0D;
        var feet=(baritone.api.pathing.goals.GoalNear)towards.invoke(process,drop);
        assertEquals(new BlockPos(10,64,-4),feet.getGoalPos());
        assertTrue(feet.isInGoal(12,64,-4));assertFalse(feet.isInGoal(13,64,-4));
        settings.followOffsetDistance.value=4D;settings.followOffsetDirection.value=0F;
        var offset=(baritone.api.pathing.goals.GoalNear)towards.invoke(process,drop);
        assertEquals(new BlockPos(10,64,0),offset.getGoalPos());
        assertTrue(offset.isInGoal(10,66,0));assertFalse(offset.isInGoal(10,67,0));
    }
    private static <T>T allocate(Class<T> type) throws Exception {
        Class<?> unsafeClass=Class.forName("sun.misc.Unsafe",true,ClassLoader.getSystemClassLoader());
        Field field=unsafeClass.getDeclaredField("theUnsafe");field.setAccessible(true);
        Object unsafe=field.get(null);return type.cast(unsafeClass.getMethod("allocateInstance",Class.class).invoke(unsafe,type));
    }
    private static void setObject(Object target,Field field,Object value) throws Exception {
        Class<?> unsafeClass=Class.forName("sun.misc.Unsafe",true,ClassLoader.getSystemClassLoader());
        Field unsafeField=unsafeClass.getDeclaredField("theUnsafe");unsafeField.setAccessible(true);Object unsafe=unsafeField.get(null);
        long offset=((Number)unsafeClass.getMethod("objectFieldOffset",Field.class).invoke(unsafe,field)).longValue();
        unsafeClass.getMethod("putObject",Object.class,long.class,Object.class).invoke(unsafe,target,offset,value);
    }
}
