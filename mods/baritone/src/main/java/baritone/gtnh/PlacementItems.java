// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh;

import baritone.compat.Registry;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;

/** Conservative defaults for automatic support construction; no machines or valuable blocks. */
final class PlacementItems {
    private static final Set<String> DEFAULTS=Set.of("minecraft:cobblestone","minecraft:stone","minecraft:dirt","minecraft:netherrack");
    static boolean usable(ItemStack stack) {
        return stack!=null && stack.stackSize>0 && stack.getItem() instanceof ItemBlock && stack.getItemDamage()==0
            && !stack.hasTagCompound() && DEFAULTS.contains(Registry.name(stack.getItem()));
    }
    static int slot() {
        var inventory=Minecraft.getMinecraft().thePlayer.inventory;
        if(usable(inventory.getCurrentItem())) return inventory.currentItem;
        for(int i=0;i<36;i++) if(usable(inventory.getStackInSlot(i))) return i;
        return -1;
    }
}
