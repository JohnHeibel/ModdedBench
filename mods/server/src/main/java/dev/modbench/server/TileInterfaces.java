// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.server;

import com.google.gson.*;
import dev.modbench.bridge.Json;
import net.minecraft.inventory.*;
import net.minecraft.item.*;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.*;
import java.util.*;

/** Native 1.7 inventory and fluid interfaces (this Forge has no capability API).
 * Only getters: no simulated fill/drain/insert calls or arbitrary reflective methods.
 */
final class TileInterfaces {
    static void describe(TileEntity tile,JsonObject params,JsonObject out) {
        boolean full=Json.string(params,"detail","summary").equals("full");JsonObject errors=new JsonObject();
        Set<String> interfaces=new TreeSet<>();collect(tile.getClass(),interfaces);out.add("interfaces",Json.GSON.toJsonTree(interfaces));
        out.add("support",Json.object("inventory",tile instanceof IInventory,"fluids",tile instanceof IFluidHandler));
        if(tile instanceof IInventory inv)try {
            int count=inv.getSizeInventory(),offset=Json.integer(params,"inventoryOffset",0,0,1000000),limit=Json.integer(params,"inventoryLimit",full?256:64,1,256);
            JsonArray stacks=new JsonArray();for(int i=offset;i<Math.min(count,(long)offset+limit);i++)stacks.add(stack(inv.getStackInSlot(i)));
            out.add("inventory",stacks);JsonObject info=Json.object("slots",count,"offset",offset,"returned",stacks.size(),"truncated",offset>0||offset+stacks.size()<count,"stackLimit",inv.getInventoryStackLimit());
            if(inv instanceof ISidedInventory sided){JsonArray sides=new JsonArray();for(ForgeDirection side:ForgeDirection.VALID_DIRECTIONS)try{int[] slots=sided.getAccessibleSlotsFromSide(side.ordinal());sides.add(Json.object("side",side.name(),"slots",slots==null?null:Arrays.copyOf(slots,Math.min(slots.length,4096)),"truncated",slots!=null&&slots.length>4096));}catch(Exception|LinkageError e){sides.add(Json.object("side",side.name(),"error",error(e)));}info.add("sides",sides);}
            out.add("inventoryInfo",info);
        }catch(Exception|LinkageError e){errors.addProperty("inventory",error(e));}
        if(tile instanceof IFluidHandler handler) {
            JsonArray views=new JsonArray();
            for(ForgeDirection side:ForgeDirection.values()) {
                JsonObject view=Json.object("side",side.name(),"sideIndex",side.ordinal());
                try{FluidTankInfo[] infos=handler.getTankInfo(side);if(infos==null)throw new IllegalStateException("getTankInfo returned null (unknown, not empty)");
                    JsonArray tanks=new JsonArray();for(int i=0;i<Math.min(infos.length,64);i++) {
                        FluidTankInfo t=infos[i];tanks.add(t==null?Json.object("index",i,"error","null tank info"):Json.object("index",i,"capacity",t.capacity,"empty",t.fluid==null||t.fluid.amount==0,"fluid",fluid(t.fluid)));
                    }
                    view.add("tanks",tanks);view.addProperty("count",infos.length);view.addProperty("truncated",infos.length>64);
                    if(side==ForgeDirection.UNKNOWN){out.add("tanks",tanks);out.addProperty("tanksTruncated",infos.length>64);}
                }catch(Exception|LinkageError e){view.addProperty("error",error(e));}
                views.add(view);
            }
            out.add("fluids",Json.object("unit","mB","views",views,"aggregation","side views may overlap; do not sum views","tanksAlias","UNKNOWN view only; missing or errors mean unknown, not zero"));
        }
        JsonArray energy=new JsonArray();
        adapter(tile,energy,"gregtech.api.interfaces.tileentity.IBasicEnergyContainer","gregtech_eu","EU",new String[]{"getStoredEU","getEUCapacity"},new String[]{"stored","capacity"});
        adapter(tile,energy,"ic2.api.tile.IEnergyStorage","ic2_eu","EU",new String[]{"getStored","getCapacity","getOutput"},new String[]{"stored","capacity","output"});
        // RF storage/receiver/provider interfaces expose side-dependent readings.
        for(String name:new String[]{"cofh.api.energy.IEnergyReceiver","cofh.api.energy.IEnergyProvider"})try{
            Class<?> api=Class.forName(name);if(!api.isInstance(tile))continue;JsonArray views=new JsonArray();
            for(ForgeDirection side:ForgeDirection.values()){JsonObject view=Json.object("side",side.name());try{view.add("stored",Json.GSON.toJsonTree(api.getMethod("getEnergyStored",ForgeDirection.class).invoke(tile,side)));view.add("capacity",Json.GSON.toJsonTree(api.getMethod("getMaxEnergyStored",ForgeDirection.class).invoke(tile,side)));}catch(Exception|LinkageError e){view.addProperty("error",error(e));}views.add(view);}
            energy.add(Json.object("adapter",name,"unit","RF","views",views,"aggregation","side views may overlap; do not sum views"));
        }catch(ClassNotFoundException ignored){}catch(Exception|LinkageError e){errors.addProperty(name,error(e));}
        out.add("energy",energy);if(errors.entrySet().size()>0)out.add("interfaceErrors",errors);
    }
    private static void adapter(TileEntity tile,JsonArray out,String className,String name,String unit,String[] methods,String[] keys) {
        try{Class<?> api=Class.forName(className);if(!api.isInstance(tile))return;JsonObject row=Json.object("adapter",name,"unit",unit);
            for(int i=0;i<methods.length;i++)try{row.add(keys[i],Json.GSON.toJsonTree(api.getMethod(methods[i]).invoke(tile)));}catch(Exception|LinkageError e){row.addProperty(keys[i]+"Error",error(e));}out.add(row);
        }catch(ClassNotFoundException ignored){}catch(LinkageError e){out.add(Json.object("adapter",name,"error",error(e)));}
    }
    private static void collect(Class<?> c,Set<String> out){if(c==null||out.size()>128)return;for(Class<?> api:c.getInterfaces()){if(out.add(api.getName()))collect(api,out);}collect(c.getSuperclass(),out);}
    static JsonElement stack(ItemStack s){if(s==null)return JsonNull.INSTANCE;return Json.object("id",Item.itemRegistry.getNameForObject(s.getItem()),"meta",s.getItemDamage(),"count",s.stackSize,"nbt",boundedTag(s.getTagCompound()));}
    static JsonElement fluid(FluidStack s){if(s==null)return JsonNull.INSTANCE;return Json.object("id",s.getFluid()==null?null:s.getFluid().getName(),"amount",s.amount,"nbt",boundedTag(s.tag));}
    private static Object boundedTag(net.minecraft.nbt.NBTTagCompound tag){if(tag==null)return null;String text=tag.toString();return text.length()<=8192?text:Json.object("elided",true,"reason","item/fluid tag exceeds 8192 characters; drill into tile NBT snapshot");}
    static String error(Throwable e){while(e.getCause()!=null)e=e.getCause();String s=e.toString();return s.substring(0,Math.min(s.length(),512));}
}
