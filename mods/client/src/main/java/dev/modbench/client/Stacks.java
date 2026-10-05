// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.client;

import com.google.gson.*;
import dev.modbench.bridge.Json;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.item.*;
import net.minecraft.nbt.*;

/** Lossless observed stack identity, shared by inventory, GUI and stale-state checks. */
final class Stacks {
    static JsonObject json(ItemStack stack) {
        // ME craftable-only entries have an identity with zero stored quantity.
        if(stack==null) return null;
        String name;try {name=stack.getDisplayName();}catch(RuntimeException error) {name="<display name unavailable>";}
        String nbt=stack.hasTagCompound()?canonical(stack.getTagCompound()):null;
        JsonObject out=Json.object("id",Item.itemRegistry.getNameForObject(stack.getItem()),"meta",stack.getItemDamage(),
            "count",stack.stackSize,"name",name,"maxStackSize",stack.getMaxStackSize(),"nbt",nbt);
        if(nbt!=null) {out.addProperty("nbt_hash",fingerprint(nbt));out.addProperty("nbt_bytes",nbt.getBytes(StandardCharsets.UTF_8).length);}
        if(stack.isItemStackDamageable()) out.add("dmg",Json.array(stack.getItemDamage(),stack.getMaxDamage()));
        return out;
    }
    static String fingerprint(String value) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }
    /** Compound iteration order can change on native copy/pickup. Never change the live tag. */
    static String canonical(NBTBase tag) {
        if(tag instanceof NBTTagCompound compound) {
            SortedSet<String> keys=new TreeSet<>();
            for(Object key:compound.func_150296_c()) keys.add((String)key);
            StringJoiner out=new StringJoiner(",","{","}");
            for(String key:keys) out.add(key+":"+canonical(compound.getTag(key)));
            return out.toString();
        }
        if(tag instanceof NBTTagList list) {
            // 1.7.10 has no generic list getter; consume a copy backwards in linear time.
            NBTTagList copy=(NBTTagList)list.copy();String[] values=new String[copy.tagCount()];
            for(int i=values.length-1;i>=0;i--) values[i]=i+":"+canonical(copy.removeTag(i));
            return "["+String.join(",",values)+"]";
        }
        return tag.toString();
    }
    static boolean same(ItemStack a,ItemStack b) {
        return a!=null&&b!=null&&a.getItem()==b.getItem()&&a.getItemDamage()==b.getItemDamage()&&ItemStack.areItemStackTagsEqual(a,b);
    }
    static boolean expected(ItemStack stack,JsonElement expected) {
        return Json.GSON.toJsonTree(json(stack)).equals(expected);
    }
    static boolean matches(ItemStack stack,JsonObject selector) {
        for(var entry:selector.entrySet()) if(!Set.of("id","meta","nbt_hash","nbt").contains(entry.getKey())) throw new IllegalArgumentException("unknown selector field: "+entry.getKey());
        if(!selector.has("id")) throw new IllegalArgumentException("selector.id required");
        if(stack==null) return false;
        if(!Json.string(selector,"id","").equals(Item.itemRegistry.getNameForObject(stack.getItem()))) return false;
        if(selector.has("meta")&&Json.integer(selector,"meta",0,0,Integer.MAX_VALUE)!=stack.getItemDamage()) return false;
        String nbt=stack.hasTagCompound()?canonical(stack.getTagCompound()):null;
        if(selector.has("nbt_hash")&&!Json.string(selector,"nbt_hash","").equals(nbt!=null?fingerprint(nbt):"")) return false;
        if(selector.has("nbt")&&!Objects.equals(selector.get("nbt"),Json.GSON.toJsonTree(nbt))) return false;
        for(var entry:selector.entrySet()) if(!Set.of("id","meta","nbt_hash","nbt").contains(entry.getKey())) throw new IllegalArgumentException("unknown selector field: "+entry.getKey());
        return true;
    }
    static JsonObject tooltip(ItemStack stack,boolean advanced) {
        List<String> lines=new ArrayList<>();String error=null;
        if(stack!=null) try {
            for(Object line:stack.getTooltip(Minecraft.getMinecraft().thePlayer,advanced)) lines.add(UiWidgets.strip(String.valueOf(line)));
        } catch(RuntimeException unavailable) {error=unavailable.toString();}
        return Json.object("stack",json(stack),"lines",lines,"error",error);
    }
}
