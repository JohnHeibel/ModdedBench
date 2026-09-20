// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
package dev.modbench.server;

import com.google.gson.*;
import dev.modbench.bridge.Json;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.nbt.*;
import net.minecraft.world.World;
import java.util.*;

/** 1.7 Waila's normal server provider chain, kept separate from native tile NBT. */
final class WailaServer {
    static JsonObject read(EntityPlayerMP player,TileEntity tile,Object block,int x,int y,int z,NBTTagCompound snapshot) {
        JsonArray providers=new JsonArray(),errors=new JsonArray();JsonObject out=Json.object("available",false,"providers",providers,"errors",errors);
        out.add("providers",providers);out.add("errors",errors);
        try {
            Class<?> registry=Class.forName("mcp.mobius.waila.api.impl.ModuleRegistrar");Object registrar=registry.getMethod("instance").invoke(null);
            Class<?> providerApi=Class.forName("mcp.mobius.waila.api.IWailaDataProvider");
            var get=registry.getMethod("getNBTProviders",Object.class);
            var call=providerApi.getMethod("getNBTData",EntityPlayerMP.class,TileEntity.class,NBTTagCompound.class,World.class,int.class,int.class,int.class);
            NBTTagCompound tag=new NBTTagCompound();boolean any=false;
            for(Object key:new Object[]{block,tile})if(key!=null) {
                Object found=get.invoke(registrar,key);if(!(found instanceof Map<?,?> map))continue;
                // Preserve registrar priority ordering, including its block then tile passes.
                for(Object bucket:map.values())for(Object provider:(Iterable<?>)bucket) {
                    any=true;String name=provider.getClass().getName();
                    try{Object next=call.invoke(provider,player,tile,tag,player.worldObj,x,y,z);if(!(next instanceof NBTTagCompound))throw new IllegalStateException("provider returned null NBT");tag=(NBTTagCompound)next;providers.add(new JsonPrimitive(name));}
                    catch(Exception|LinkageError e){errors.add(Json.object("provider",name,"error",TileInterfaces.error(e)));}
                }
            }
            if(!any)tag=snapshot==null?new NBTTagCompound():(NBTTagCompound)snapshot.copy();
            // Native Message0x01TERequest adds these after providers. Without
            // them DataAccessorCommon silently rejects remote NBT and renders
            // a client writeToNBT fallback (often zeros for Waila-only fields).
            tag.setInteger("WailaX",x);tag.setInteger("WailaY",y);tag.setInteger("WailaZ",z);
            if(snapshot!=null&&snapshot.hasKey("id"))tag.setString("id",snapshot.getString("id"));
            var bytes=new java.io.ByteArrayOutputStream();CompressedStreamTools.writeCompressed(tag,bytes);
            if(bytes.size()>262144)throw new IllegalArgumentException("Waila provider data exceeds 256KiB");
            out.addProperty("available",true);out.addProperty("data",Base64.getEncoder().encodeToString(bytes.toByteArray()));
        }catch(ClassNotFoundException e){out.addProperty("reason","Waila is not installed on the server");}
        catch(Exception|LinkageError e){out.addProperty("error",TileInterfaces.error(e));}
        return out;
    }
}
