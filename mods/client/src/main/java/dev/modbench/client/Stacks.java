// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
package dev.modbench.client;

import com.google.gson.*;
import dev.modbench.bridge.Json;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.item.*;

/** Lossless observed stack identity, shared by inventory, GUI and stale-state checks. */
final class Stacks {
    static JsonObject json(ItemStack stack) {
        // ME craftable-only entries have an identity with zero stored quantity.
        if(stack==null) return null;
        String name;try {name=stack.getDisplayName();}catch(RuntimeException error) {name="<display name unavailable>";}
        String nbt=stack.hasTagCompound()?stack.getTagCompound().toString():null;
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
        if(selector.has("nbt_hash")&&!Json.string(selector,"nbt_hash","").equals(stack.hasTagCompound()?fingerprint(stack.getTagCompound().toString()):"")) return false;
        if(selector.has("nbt")&&!Objects.equals(selector.get("nbt"),Json.GSON.toJsonTree(stack.hasTagCompound()?stack.getTagCompound().toString():null))) return false;
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
