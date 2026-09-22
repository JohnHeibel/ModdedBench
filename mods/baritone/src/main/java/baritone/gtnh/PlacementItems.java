// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh;

import baritone.Baritone;
import baritone.compat.Registry;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;

/** What a placement may spend: the item selectors the caller names, or by default the model-owned acceptableThrowawayItems
 *  setting. Whether a stack places a block is the game's to say: the job right-clicks with it and looks at the world. */
final class PlacementItems {
    private PlacementItems(){}
    static boolean usable(ItemStack stack,List<Map<String,Object>> selectors) {
        if(stack==null||stack.stackSize<=0)return false;
        if(selectors!=null)return selectors.stream().anyMatch(s->WorkAccess.item(stack,s));
        return Baritone.settings().acceptableThrowawayItems.value.contains(stack.getItem());
    }
    static int slot(List<Map<String,Object>> selectors) {
        var inventory=Minecraft.getMinecraft().thePlayer.inventory;
        if(usable(inventory.getCurrentItem(),selectors)) return inventory.currentItem;
        for(int i=0;i<36;i++) if(usable(inventory.getStackInSlot(i),selectors)) return i;
        return -1;
    }
    /** The rule in force, for the receipt. */
    static Object rule(List<Map<String,Object>> selectors) {
        if(selectors!=null)return Map.of("items",selectors);
        return Map.of("setting","acceptableThrowawayItems","items",Baritone.settings().acceptableThrowawayItems.value.stream().map(Registry::name).toList());
    }
}
