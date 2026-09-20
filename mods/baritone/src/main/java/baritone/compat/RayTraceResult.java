// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.compat;

public final class RayTraceResult {
    public enum Type { MISS,BLOCK,ENTITY }
    public final Type typeOfHit; public final EnumFacing sideHit; public final Vec3d hitVec; private final BlockPos pos;
    private RayTraceResult(net.minecraft.util.MovingObjectPosition hit){typeOfHit=switch(hit.typeOfHit){case BLOCK->Type.BLOCK;case ENTITY->Type.ENTITY;default->Type.MISS;};sideHit=hit.sideHit<0?null:EnumFacing.getFront(hit.sideHit);hitVec=Vec3d.fromNative(hit.hitVec);pos=new BlockPos(hit.blockX,hit.blockY,hit.blockZ);}
    public BlockPos getBlockPos(){return pos;}
    public static RayTraceResult fromNative(net.minecraft.util.MovingObjectPosition hit){return hit==null?null:new RayTraceResult(hit);}
}
