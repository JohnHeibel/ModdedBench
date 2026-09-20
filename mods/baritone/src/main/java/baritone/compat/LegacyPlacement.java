// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.compat;

import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.gtnh.PlacementStateAdapters;
import dev.modbench.control.NativePlacement;
import net.minecraft.block.Block;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;

/** Native placement prediction shared by source builder and its approximate material inventory. */
public final class LegacyPlacement {
    private LegacyPlacement(){}
    public static IBlockState predict(IPlayerContext ctx,ItemStack stack,BlockPos target,EnumFacing face,float hitX,float hitY,float hitZ,float yaw){
        return predict(ctx,stack,target,face,hitX,hitY,hitZ,yaw,ctx.player().getPosition(1));
    }
    private static IBlockState predict(IPlayerContext ctx,ItemStack stack,BlockPos target,EnumFacing face,float hitX,float hitY,float hitZ,float yaw,net.minecraft.util.Vec3 eye){
        if(stack==null||stack.stackSize<=0||!(stack.getItem() instanceof ItemBlock))return IBlockState.of(Blocks.AIR,0);
        Block block=Block.getBlockFromItem(stack.getItem());
        int meta=block.onBlockPlaced(ctx.world().nativeWorld,target.getX(),target.getY(),target.getZ(),face.ordinal(),hitX,hitY,hitZ,NativePlacement.initialMetadata(stack));
        meta=PlacementStateAdapters.predict(block,ctx.world().nativeWorld,new baritone.gtnh.pathing.BlockPos(target.getX(),target.getY(),target.getZ()),meta,yaw,eye);
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
