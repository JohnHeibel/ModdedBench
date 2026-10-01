// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.modbench.bridge.Json;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

/** Replays in a clone of a played world: no course is built and nothing in the world is changed. The only player is
 *  moved to a start, and the world is read back (the player, and named cells) as the server sees it. */
final class ReplayFixture {
    private final MinecraftServer server;
    ReplayFixture(MinecraftServer server){this.server=server;}
    private WorldServer world(){return server.worldServerForDimension(0);}
    private EntityPlayerMP player(){
        EntityPlayerMP found=null;
        for(Object o:server.getConfigurationManager().playerEntityList){
            EntityPlayerMP p=(EntityPlayerMP)o;
            if(p.playerNetServerHandler==null||!p.playerNetServerHandler.netManager.isChannelOpen()||p.isDead)continue;
            if(found!=null)throw new IllegalArgumentException("replay needs exactly one connected player");
            found=p;
        }
        if(found==null)throw new IllegalArgumentException("replay needs a connected player");
        if(found.dimension!=0)throw new IllegalArgumentException("replay player must be in the overworld");
        return found;
    }
    /** {x,y,z (feet, exact), yaw?, pitch?}: loads the chunks round the start, then teleports with no fall or motion. */
    Object place(JsonObject p){
        EntityPlayerMP pl=player();
        double x=Json.number(p,"x",0,-3e7,3e7),y=Json.number(p,"y",64,1,255),z=Json.number(p,"z",0,-3e7,3e7);
        for(int cx=((int)Math.floor(x)>>4)-2;cx<=((int)Math.floor(x)>>4)+2;cx++)for(int cz=((int)Math.floor(z)>>4)-2;cz<=((int)Math.floor(z)>>4)+2;cz++)world().theChunkProviderServer.loadChunk(cx,cz);
        pl.fallDistance=0;pl.motionX=pl.motionY=pl.motionZ=0;pl.extinguish();
        pl.playerNetServerHandler.setPlayerLocation(x,y,z,(float)Json.number(p,"yaw",0,-360,360),(float)Json.number(p,"pitch",0,-90,90));
        return status(p);
    }
    /** The player as the server sees it, and {cells:[[x,y,z],...]} (at most 64): block id and meta. */
    Object status(JsonObject p){
        EntityPlayerMP pl=player();
        JsonArray inv=new JsonArray();
        for(int i=0;i<pl.inventory.mainInventory.length;i++){ItemStack s=pl.inventory.mainInventory[i];
            if(s!=null)inv.add(Json.object("slot",i,"id",Item.itemRegistry.getNameForObject(s.getItem()),"meta",s.getItemDamage(),"count",s.stackSize));}
        JsonObject out=Json.object("name",pl.getCommandSenderName(),"pos",Json.array(pl.posX,pl.posY,pl.posZ),"health",pl.getHealth(),"food",pl.getFoodStats().getFoodLevel(),
            "dead",pl.isDead||pl.getHealth()<=0,"burning",pl.isBurning(),"held",pl.inventory.currentItem,"inventory",inv,"serverTick",server.getTickCounter());
        JsonArray cells=new JsonArray();
        if(p.has("cells")&&p.get("cells").isJsonArray()){
            int n=0;
            for(JsonElement e:p.getAsJsonArray("cells")){
                if(n++>=64)break;
                JsonArray c=e.getAsJsonArray();int x=c.get(0).getAsInt(),y=c.get(1).getAsInt(),z=c.get(2).getAsInt();
                Block b=world().getBlock(x,y,z);
                cells.add(Json.object("pos",Json.array(x,y,z),"block",String.valueOf(Block.blockRegistry.getNameForObject(b)),"meta",world().getBlockMetadata(x,y,z),
                    "loaded",world().getChunkProvider().chunkExists(x>>4,z>>4)));
            }
        }
        out.add("cells",cells);
        return out;
    }
}
