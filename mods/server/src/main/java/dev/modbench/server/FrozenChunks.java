// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
package dev.modbench.server;

import java.util.ArrayList;
import java.util.Iterator;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S26PacketMapChunkBulk;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.ChunkWatchEvent;

/** The chunk-delivery portion of EntityPlayerMP.onUpdate, without ticking the player. */
final class FrozenChunks {
    static void send(EntityPlayerMP player) {
        if(player.loadedChunks.isEmpty()) return;
        WorldServer world=player.getServerForPlayer();
        ArrayList<Chunk> chunks=new ArrayList<>();ArrayList<TileEntity> tiles=new ArrayList<>();
        Iterator iterator=player.loadedChunks.iterator();
        while(iterator.hasNext() && chunks.size()<S26PacketMapChunkBulk.func_149258_c()) {
            ChunkCoordIntPair pos=(ChunkCoordIntPair)iterator.next();
            if(pos==null) { iterator.remove();continue; }
            if(!world.blockExists(pos.chunkXPos<<4,0,pos.chunkZPos<<4)) continue;
            Chunk chunk=world.getChunkFromChunkCoords(pos.chunkXPos,pos.chunkZPos);
            if(!chunk.func_150802_k()) continue;
            chunks.add(chunk);iterator.remove();
            tiles.addAll(world.func_147486_a(pos.chunkXPos*16,0,pos.chunkZPos*16,pos.chunkXPos*16+15,256,pos.chunkZPos*16+15));
        }
        if(chunks.isEmpty()) return;
        player.playerNetServerHandler.sendPacket(new S26PacketMapChunkBulk(chunks));
        for(TileEntity tile:tiles) { Packet packet=tile.getDescriptionPacket();if(packet!=null) player.playerNetServerHandler.sendPacket(packet); }
        for(Chunk chunk:chunks) {
            world.getEntityTracker().func_85172_a(player,chunk);
            MinecraftForge.EVENT_BUS.post(new ChunkWatchEvent.Watch(chunk.getChunkCoordIntPair(),player));
        }
    }
}
