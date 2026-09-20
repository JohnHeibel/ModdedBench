// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.client;

import dev.modbench.api.ControlRegistry;
import com.google.gson.*;
import dev.modbench.bridge.Json;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.util.*;
import net.minecraft.world.World;
import java.util.*;
import java.lang.reflect.*;

/** Off-HUD traversal of the Waila 1.7 providers GTNH ships.
 * Uses an isolated accessor/TipLists so observations don't replace the HUD target.
 */
final class WailaObservation {
    @SuppressWarnings("unchecked")
    static JsonObject read(JsonObject block,JsonObject params,JsonObject server) {
        JsonArray used=new JsonArray(),errors=new JsonArray();
        JsonObject out=Json.object("available",false,"providers",used,"errors",errors,"serverProviders",server.get("providers"),"serverErrors",server.get("errors"),"lines",new JsonArray());
        out.add("providers",used);out.add("errors",errors);
        if(!Json.bool(server,"available",false)){out.add("server",server);return out;}
        try {
            var mc=Minecraft.getMinecraft();JsonArray p=block.getAsJsonArray("pos");int x=p.get(0).getAsInt(),y=p.get(1).getAsInt(),z=p.get(2).getAsInt();
            if(!mc.theWorld.blockExists(x,y,z))throw new IllegalArgumentException("Waila client chunk unavailable");
            var nativeBlock=mc.theWorld.getBlock(x,y,z);var tile=mc.theWorld.getTileEntity(x,y,z);
            if(!block.get("id").getAsString().equals(net.minecraft.block.Block.blockRegistry.getNameForObject(nativeBlock))||block.get("meta").getAsInt()!=mc.theWorld.getBlockMetadata(x,y,z))throw new IllegalArgumentException("client block does not match server snapshot yet");
            Class<?> registry=Class.forName("mcp.mobius.waila.api.impl.ModuleRegistrar");Object registrar=registry.getMethod("instance").invoke(null);
            Class<?> accessorClass=Class.forName("mcp.mobius.waila.api.impl.DataAccessorCommon");Object accessor=accessorClass.getConstructor().newInstance();
            Class<?> api=Class.forName("mcp.mobius.waila.api.IWailaDataProvider"),dataApi=Class.forName("mcp.mobius.waila.api.IWailaDataAccessor"),configApi=Class.forName("mcp.mobius.waila.api.IWailaConfigHandler");
            Object config=Class.forName("mcp.mobius.waila.api.impl.ConfigHandler").getMethod("instance").invoke(null);
            int side=Json.integer(params,"side",1,0,5);MovingObjectPosition hit=mc.objectMouseOver;
            boolean actual=hit!=null&&hit.typeOfHit==MovingObjectPosition.MovingObjectType.BLOCK&&hit.blockX==x&&hit.blockY==y&&hit.blockZ==z;
            if(!actual)hit=new MovingObjectPosition(x,y,z,side,Vec3.createVectorHelper(x+.5,y+.5,z+.5));
            out.addProperty("target",actual?"crosshair":"explicit_position");out.addProperty("side",hit.sideHit);
            accessorClass.getMethod("set",World.class,EntityPlayer.class,MovingObjectPosition.class).invoke(accessor,mc.theWorld,mc.thePlayer,hit);
            byte[] bytes=Base64.getDecoder().decode(server.get("data").getAsString());
            NBTTagCompound nbt=CompressedStreamTools.readCompressed(new java.io.ByteArrayInputStream(bytes));
            accessorClass.getMethod("setNBTData",NBTTagCompound.class).invoke(accessor,nbt);
            MovingObjectPosition pickHit=hit;ItemStack stack=null;
            try{stack=ControlRegistry.targeting().withContext(()->nativeBlock.getPickBlock(pickHit,mc.theWorld,x,y,z,mc.thePlayer));}catch(Exception|LinkageError e){errors.add(Json.object("stage","pick","error",error(e)));}
            for(Object provider:providers(registry,registrar,"getStackProviders",nativeBlock,tile))try{
                Object next=api.getMethod("getWailaStack",dataApi,configApi).invoke(provider,accessor,config);used.add(new JsonPrimitive(provider.getClass().getName()));if(next instanceof ItemStack selected){stack=selected;break;}
            }catch(Exception|LinkageError e){errors.add(Json.object("provider",provider.getClass().getName(),"stage","stack","error",error(e)));}
            accessorClass.getField("stack").set(accessor,stack);
            JsonArray all=new JsonArray();int remaining=32768;
            for(String stage:new String[]{"Head","Body","Tail"}) {
                List<String> lines=(List<String>)Class.forName("mcp.mobius.waila.api.impl.TipList").getConstructor().newInstance();
                Method call=api.getMethod("getWaila"+stage,ItemStack.class,List.class,dataApi,configApi);
                for(Object provider:providers(registry,registrar,"get"+stage+"Providers",nativeBlock,tile))try {
                    Object next=call.invoke(provider,stack,lines,accessor,config);if(next instanceof List<?> list&&next!=lines){lines.clear();for(Object line:list)lines.add(String.valueOf(line));}used.add(new JsonPrimitive(provider.getClass().getName()));
                }catch(Exception|LinkageError e){errors.add(Json.object("provider",provider.getClass().getName(),"stage",stage,"error",error(e)));}
                JsonArray section=new JsonArray();
                for(Object raw:lines){String line=String.valueOf(raw);if(all.size()>=256||line.length()>remaining){out.addProperty("truncated",true);break;}remaining-=line.length();section.add(new JsonPrimitive(line));all.add(new JsonPrimitive(line.replaceAll("§.","")));}
                out.add(stage.toLowerCase(Locale.ROOT),section);
            }
            out.add("lines",all);out.addProperty("available",true);out.addProperty("source","native Waila server NBT and client providers; config and sneak state match the player");
        }catch(ClassNotFoundException e){out.addProperty("reason","Waila is not installed on the client");}
        catch(Exception|LinkageError e){out.addProperty("error",error(e));}
        return out;
    }
    private static List<Object> providers(Class<?> registry,Object registrar,String getter,Object... keys)throws Exception{
        List<Object> out=new ArrayList<>();for(Object key:keys)if(key!=null){Object found=registry.getMethod(getter,Object.class).invoke(registrar,key);if(found instanceof Map<?,?> map)for(Object bucket:map.values())for(Object p:(Iterable<?>)bucket)out.add(p);}return out;
    }
    private static String error(Throwable e){while(e.getCause()!=null)e=e.getCause();String s=e.toString();return s.substring(0,Math.min(512,s.length()));}
}
