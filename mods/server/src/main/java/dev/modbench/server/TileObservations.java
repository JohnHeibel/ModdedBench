// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
package dev.modbench.server;

import com.google.gson.*;
import dev.modbench.bridge.Json;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import java.util.Set;

/** Read-only authoritative world observations on the server maintenance thread.
 * No chunk loads, dimension switching, client-only pick-block or gameplay writes.
 */
final class TileObservations {
    static final Set<String> METHODS=Set.of("obs.tile","obs.nbt","obs.waila");
    private final ServerRuntime runtime;
    private final NbtSnapshots snapshots=new NbtSnapshots();
    TileObservations(ServerRuntime runtime){this.runtime=runtime;}
    JsonObject batch(EntityPlayerMP player,JsonObject request) {
        String world=WorldIdentity.get(player.mcServer.worldServers[0]);
        if(!world.equals(Json.string(request,"worldId",""))||player.dimension!=Json.integer(request,"dimension",Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MAX_VALUE))throw new IllegalArgumentException("observation world/dimension changed; observe again");
        JsonObject queries=request.getAsJsonObject("queries");if(queries==null||queries.entrySet().size()>16)throw new IllegalArgumentException("at most 16 observations per batch");
        JsonObject values=new JsonObject(),errors=new JsonObject();
        for(var entry:queries.entrySet())try {
            JsonObject q=entry.getValue().getAsJsonObject();String method=Json.string(q,"method","");if(!METHODS.contains(method))throw new IllegalArgumentException("not a server observation: "+method);
            JsonObject params=q.has("params")?q.getAsJsonObject("params"):new JsonObject();
            JsonObject value=method.equals("obs.nbt")?snapshots.read(params,player.getUniqueID().toString(),world+"|"+player.dimension):tile(player,params,world,method);
            values.add(entry.getKey(),value);
        }catch(Exception|LinkageError e){errors.add(entry.getKey(),Json.object("code","observation_failed","msg",TileInterfaces.error(e)));}
        return Json.object("values",values,"errors",errors,"serverTick",runtime.tick(),"worldId",world,"dimension",player.dimension,"src","server");
    }
    private JsonObject tile(EntityPlayerMP player,JsonObject params,String worldId,String method) throws Exception {
        JsonObject coords=params;
        if(params.has("pos")){JsonArray p=params.getAsJsonArray("pos");if(p.size()!=3)throw new IllegalArgumentException("pos must be [x,y,z]");coords=Json.object("x",p.get(0),"y",p.get(1),"z",p.get(2));}
        if(!coords.has("x")||!coords.has("y")||!coords.has("z"))throw new IllegalArgumentException("position required");
        int x=Json.integer(coords,"x",0,-30000000,30000000),y=Json.integer(coords,"y",0,0,255),z=Json.integer(coords,"z",0,-30000000,30000000);
        if(params.has("dimension")&&Json.integer(params,"dimension",player.dimension,Integer.MIN_VALUE,Integer.MAX_VALUE)!=player.dimension)throw new IllegalArgumentException("observation must be in player's current dimension");
        if(player.getDistanceSq(x+.5,y+.5,z+.5)>128*128)throw new IllegalArgumentException("block outside 128-block observation radius");
        World world=player.worldObj;if(!world.blockExists(x,y,z))throw new IllegalArgumentException("chunk not loaded");
        Block block=world.getBlock(x,y,z);var tile=world.getTileEntity(x,y,z);
        JsonObject provenance=Json.object("serverTick",runtime.tick(),"worldId",worldId,"dimension",player.dimension,"bridgeId",runtime.bridgeId(),"worldTime",world.getWorldTime(),"totalTime",world.getTotalWorldTime(),"pos",Json.array(x,y,z));
        JsonObject out=Json.object("id",Block.blockRegistry.getNameForObject(block),"meta",world.getBlockMetadata(x,y,z),"pos",Json.array(x,y,z),"dimension",player.dimension,"src","server","provenance",provenance,"hasTile",tile!=null,"tileClass",tile==null?null:tile.getClass().getName());
        NBTTagCompound tag=null;
        if(tile!=null) {
            out.addProperty("invalid",tile.isInvalid());TileInterfaces.describe(tile,params,out);
            try{tag=new NBTTagCompound();tile.writeToNBT(tag);String handle=snapshots.remember(tag,player.getUniqueID().toString(),worldId+"|"+player.dimension,provenance);
                JsonObject nbt=NbtSnapshots.describe(tag,params);out.add("tile",nbt.remove("value"));nbt.addProperty("handle",handle);nbt.addProperty("snapshot",true);out.add("nbt",nbt);
            }catch(Exception|LinkageError e){out.add("nbt",Json.object("error",TileInterfaces.error(e)));tag=null;}
        }
        if(method.equals("obs.waila")||Json.bool(params,"hwyla",true))out.add("waila",WailaServer.read(player,tile,block,x,y,z,tag));
        return out;
    }
}
