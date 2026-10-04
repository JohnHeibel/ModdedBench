// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.compat;

import dev.modbench.api.ControlRegistry;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.gtnh.PlacementStateAdapters;
import net.minecraft.block.Block;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;

/** Native placement prediction shared by source builder and its approximate material inventory. */
public final class LegacyPlacement {
    private LegacyPlacement(){}
    /**
     * The one meaning of an empty cell: a block placed into it replaces what is there (air, tall grass, a snow layer,
     * water). It is the game's own answer, the one ItemBlock asks before it decides where a click lands.
     */
    public static boolean empty(net.minecraft.world.World world,int x,int y,int z){Block b=world.getBlock(x,y,z);return b.isAir(world,x,y,z)||b.isReplaceable(world,x,y,z);}
    /** Where a block placed by a click on `side` of `clicked` lands: in the clicked cell itself when that is empty, else beside it. */
    public static BlockPos landing(boolean clickedEmpty,BlockPos clicked,EnumFacing side){return clickedEmpty?clicked:clicked.offset(side);}
    public static IBlockState predict(IPlayerContext ctx,ItemStack stack,BlockPos target,EnumFacing face,float hitX,float hitY,float hitZ,float yaw){
        return predict(ctx,stack,target,face,hitX,hitY,hitZ,yaw,ctx.player().getPosition(1));
    }
    private static IBlockState predict(IPlayerContext ctx,ItemStack stack,BlockPos target,EnumFacing face,float hitX,float hitY,float hitZ,float yaw,net.minecraft.util.Vec3 eye){
        if(stack==null||stack.stackSize<=0||!(stack.getItem() instanceof ItemBlock))return IBlockState.of(Blocks.AIR,0);
        Block block=Block.getBlockFromItem(stack.getItem());
        int meta=block.onBlockPlaced(ctx.world().nativeWorld,target.getX(),target.getY(),target.getZ(),face.ordinal(),hitX,hitY,hitZ,ControlRegistry.placement().initialMetadata(stack));
        meta=PlacementStateAdapters.predict(block,ctx.world().nativeWorld,new baritone.compat.BlockPos(target.getX(),target.getY(),target.getZ()),meta,yaw,eye);
        return new IBlockState(block,meta,ctx.world().nativeWorld,target.getX(),target.getY(),target.getZ()).withPlacementItem(stack);
    }
    public static IBlockState predict(IPlayerContext ctx,ItemStack stack,RayTraceResult hit,Rotation rotation){
        return predict(ctx,stack,hit,rotation,ctx.player().getPosition(1));
    }
    public static IBlockState predict(IPlayerContext ctx,ItemStack stack,RayTraceResult hit,Rotation rotation,net.minecraft.util.Vec3 eye){
        BlockPos against=hit.getBlockPos();
        return predict(ctx,stack,against.offset(hit.sideHit),hit.sideHit,(float)(hit.hitVec.x-against.getX()),(float)(hit.hitVec.y-against.getY()),(float)(hit.hitVec.z-against.getZ()),rotation.getYaw(),eye);
    }
}
