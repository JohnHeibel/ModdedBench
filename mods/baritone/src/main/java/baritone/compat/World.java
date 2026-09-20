// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.compat;

import net.minecraft.entity.Entity;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.EmptyChunk;
import java.util.List;

/** Native world calls kept outside the imported movement/search implementation. */
public final class World {
    public final net.minecraft.world.World nativeWorld;
    public final WorldProvider provider;
    public final List<Entity> loadedEntityList;
    public final List<net.minecraft.entity.player.EntityPlayer> playerEntities;
    @SuppressWarnings("unchecked") public World(net.minecraft.world.World world){nativeWorld=java.util.Objects.requireNonNull(world);provider=world.provider;loadedEntityList=world.loadedEntityList;playerEntities=world.playerEntities;}
    public IBlockState getBlockState(BlockPos p){return new IBlockState(nativeWorld.getBlock(p.getX(),p.getY(),p.getZ()),nativeWorld.getBlockMetadata(p.getX(),p.getY(),p.getZ()),nativeWorld,p.getX(),p.getY(),p.getZ());}
    public boolean mayPlace(net.minecraft.block.Block block,BlockPos pos,boolean ignoreCollision,EnumFacing side,Entity entity){
        return nativeWorld.canPlaceEntityOnSide(block,pos.getX(),pos.getY(),pos.getZ(),ignoreCollision,side.ordinal(),entity,net.minecraft.client.Minecraft.getMinecraft().thePlayer.getHeldItem());
    }
    public RayTraceResult rayTraceBlocks(Vec3d start,Vec3d end,boolean liquid,boolean noBox,boolean last){return RayTraceResult.fromNative(dev.modbench.control.NativeTargeting.trace(nativeWorld,start.nativeVector(),end.nativeVector(),liquid,noBox,last));}
    @SuppressWarnings("unchecked") public <T extends Entity> List<T> getEntitiesWithinAABB(Class<T> type,AxisAlignedBB box){return nativeWorld.getEntitiesWithinAABB(type,box.nativeBox());}
    public baritone.utils.pathing.BetterWorldBorder getWorldBorder(){return new baritone.utils.pathing.BetterWorldBorder();}
    public void sendQuittingDisconnectingPacket(){nativeWorld.sendQuittingDisconnectingPacket();}
    public boolean isLoaded(int x,int z){Chunk c=nativeWorld.getChunkFromChunkCoords(x>>4,z>>4);return c!=null&&!(c instanceof EmptyChunk)&&c.isChunkLoaded;}
}
