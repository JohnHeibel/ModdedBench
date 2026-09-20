// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.control;

import dev.modbench.api.PlacementInfo;
import java.util.*;
import java.util.function.ToIntFunction;
import net.minecraft.block.Block;
import net.minecraft.item.*;

/** Pure item-to-block metadata mappings. Never invokes placement against a world. */
public final class NativePlacement implements PlacementInfo {
    static final NativePlacement INSTANCE=new NativePlacement();
    private static final Map<Class<?>,ToIntFunction<ItemStack>> ADAPTERS=new LinkedHashMap<>();
    private static boolean resolved;
    private NativePlacement() {}
    public static void register(Class<? extends ItemBlock> type,ToIntFunction<ItemStack> adapter) {
        ADAPTERS.put(type,Objects.requireNonNull(adapter));
    }
    private static void resolve() {
        if(resolved)return;
        if(cpw.mods.fml.common.Loader.isModLoaded("gregtech"))try {
            Class<?> type=Class.forName("gregtech.common.blocks.ItemMachines");
            var prototypes=Class.forName("gregtech.api.GregTechAPI").getField("METATILEENTITIES");
            // ItemMachines.placeBlockAt selects a base tile type from the native
            // prototype registry. Item damage identifies the machine/pipe variant;
            // it is not the world's four-bit block metadata.
            ADAPTERS.putIfAbsent(type,stack->{
                int variant=stack.getItemDamage();if(variant<=0)return variant;
                try {
                    Object[] entries=(Object[])prototypes.get(null);
                    if(variant>=entries.length||entries[variant]==null)throw new IllegalArgumentException("unregistered native machine variant");
                    return ((Number)entries[variant].getClass().getMethod("getTileEntityBaseType").invoke(entries[variant])).intValue();
                }catch(ReflectiveOperationException error){throw new IllegalStateException("native item placement mapping unavailable",error);}
            });
        }catch(ReflectiveOperationException error){throw new IllegalStateException("GregTech item placement mapping unavailable",error);}
        resolved=true;
    }
    public int initialMetadata(Object item) {
        resolve();ItemStack stack=(ItemStack)item;ToIntFunction<ItemStack> adapter=ADAPTERS.get(stack.getItem().getClass());
        return adapter==null?stack.getItem().getMetadata(stack.getItemDamage()):adapter.applyAsInt(stack);
    }
    public Map<String,Object> describe(Object item) {
        ItemStack stack=(ItemStack)item;if(stack==null||!(stack.getItem() instanceof ItemBlock))return Map.of();
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("blockId",Block.blockRegistry.getNameForObject(Block.getBlockFromItem(stack.getItem())));
        out.put("initialBlockMeta",initialMetadata(stack));
        out.put("scope","initial native placement metadata; face, pose and post-placement callbacks may change the final state");
        if(ADAPTERS.containsKey(stack.getItem().getClass()))out.put("adapter",stack.getItem().getClass().getName());
        return out;
    }
}
