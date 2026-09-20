// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh;

import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;

/** Conservative defaults for automatic support construction; no machines or valuable blocks. */
final class PlacementItems {
    private static final Set<String> DEFAULTS=Set.of("minecraft:cobblestone","minecraft:stone","minecraft:dirt","minecraft:netherrack");
    static boolean usable(ItemStack stack) {
        return stack!=null && stack.stackSize>0 && stack.getItem() instanceof ItemBlock && stack.getItemDamage()==0
            && !stack.hasTagCompound() && DEFAULTS.contains(Item.itemRegistry.getNameForObject(stack.getItem()));
    }
    static int count() {
        int count=0;for(ItemStack stack:Minecraft.getMinecraft().thePlayer.inventory.mainInventory) if(usable(stack)) count+=stack.stackSize;return count;
    }
    static int slot() {
        var inventory=Minecraft.getMinecraft().thePlayer.inventory;
        if(usable(inventory.getCurrentItem())) return inventory.currentItem;
        for(int i=0;i<36;i++) if(usable(inventory.getStackInSlot(i))) return i;
        return -1;
    }
}
