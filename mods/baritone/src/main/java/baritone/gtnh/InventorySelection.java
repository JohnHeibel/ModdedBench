// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh;

import baritone.compat.Registry;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/** Normal hotbar selection or player-container clicks, preserving the complete stack. */
final class InventorySelection {
    private final Minecraft mc=Minecraft.getMinecraft();
    final int source,hotbar;
    final ItemStack expected;
    private final ItemStack displaced;
    private boolean started;
    private dev.modbench.api.Controls.InventorySession screen;
    private boolean opened;
    private int wait;
    InventorySelection(int source) {
        this.source=source;hotbar=hotbarSlot(source);
        ItemStack stack=mc.thePlayer.inventory.getStackInSlot(source);expected=stack==null?null:stack.copy();
        ItemStack old=mc.thePlayer.inventory.getStackInSlot(hotbar);displaced=old==null?null:old.copy();
    }
    private int hotbarSlot(int source) {
        if(source<9)return source;
        // Retain InventoryBehavior.getTempHotbarSlot's preference for an empty
        // slot. Replacing the selected material on every change forces a full
        // GUI swap again when a mixed-material build needs that material back.
        int current=mc.thePlayer.inventory.currentItem;
        if(mc.thePlayer.inventory.getStackInSlot(current)==null)return current;
        for(int i=0;i<9;i++)if(mc.thePlayer.inventory.getStackInSlot(i)==null)return i;
        return current;
    }
    boolean tick(dev.modbench.api.InputArbiter.Lease lease) {
        if(!started&&source<9&&mc.thePlayer.inventory.currentItem==source&&mc.currentScreen==null&&mc.thePlayer.inventory.getItemStack()==null&&ItemStack.areItemStacksEqual(expected,mc.thePlayer.getHeldItem())) {
            started=true;return true;
        }
        if(source>=9 && !opened) {
            screen=dev.modbench.api.ControlRegistry.controls().beginPlayerInventory(lease);opened=true;wait=2;return false;
        }
        if(screen!=null && !screen.isOpen()) throw new IllegalArgumentException("inventory_screen_changed");
        if(!started && wait>0) {wait--;return false;}
        if(!started) {
            if(mc.thePlayer.openContainer!=mc.thePlayer.inventoryContainer || mc.thePlayer.inventory.getItemStack()!=null)
                throw new IllegalArgumentException("inventory_must_be_closed_with_empty_cursor");
            if(!ItemStack.areItemStacksEqual(expected,mc.thePlayer.inventory.getStackInSlot(source))) throw new IllegalArgumentException("inventory_changed_before_selection");
            if(source>=9 && !ItemStack.areItemStacksEqual(displaced,mc.thePlayer.inventory.getStackInSlot(hotbar))) throw new IllegalArgumentException("hotbar_changed_before_selection");
            mc.playerController.resetBlockRemoving();
            if(source>=9) {
                // Resolve actual slots: packs can add slots to the player container.
                int from=containerSlot(source),to=containerSlot(hotbar),window=mc.thePlayer.inventoryContainer.windowId;
                // Complete the cursor swap synchronously; no input tick occurs between clicks.
                // The server receives ordinary survival container transactions in order.
                mc.playerController.windowClick(window,from,0,0,mc.thePlayer);
                mc.playerController.windowClick(window,to,0,0,mc.thePlayer);
                mc.playerController.windowClick(window,from,0,0,mc.thePlayer);
            }
            mc.thePlayer.inventory.currentItem=hotbar;started=true;wait=source>=9?10:1;
            if(!ItemStack.areItemStacksEqual(expected,mc.thePlayer.inventory.getStackInSlot(hotbar)))
                throw new IllegalArgumentException("inventory_swap_not_applied: "+describe(mc.thePlayer.inventory.getStackInSlot(hotbar)));
            return false;
        }
        if(mc.thePlayer.inventory.getItemStack()!=null || !ItemStack.areItemStacksEqual(expected,mc.thePlayer.inventory.getCurrentItem()) || mc.thePlayer.inventory.currentItem!=hotbar)
            throw new IllegalArgumentException("inventory_selection_changed_or_rejected: "+describe(mc.thePlayer.inventory.getCurrentItem()));
        if(source>=9 && !ItemStack.areItemStacksEqual(displaced,mc.thePlayer.inventory.getStackInSlot(source)))
            throw new IllegalArgumentException("displaced_inventory_stack_changed");
        if(--wait>0) return false;
        if(screen!=null) {
            close();wait=2;
            // Minecraft 1.7.10's focus reacquisition sets leftClickCounter=10000.
            // Let normal ticks with attack released reset it before starting a dig.
            return false;
        }
        return true;
    }
    void close() {if(screen!=null) {screen.close();screen=null;}}
    private int containerSlot(int inventorySlot) {
        for(Object entry:mc.thePlayer.inventoryContainer.inventorySlots) {
            net.minecraft.inventory.Slot slot=(net.minecraft.inventory.Slot)entry;
            if(slot.inventory==mc.thePlayer.inventory && slot.getSlotIndex()==inventorySlot) return slot.slotNumber;
        }
        throw new IllegalArgumentException("player_inventory_slot_not_found");
    }
    Map<String,Object> status() {
        Map<String,Object> out=new LinkedHashMap<>();out.put("sourceSlot",source);out.put("hotbarSlot",hotbar);out.put("swapped",source>=9);
        out.put("stack",describe(expected));return out;
    }
    static Map<String,Object> describe(ItemStack stack) {
        if(stack==null) return Map.of("empty",true);
        Map<String,Object> out=new LinkedHashMap<>();out.put("id",Registry.name(stack.getItem()));
        out.put("meta",stack.getItemDamage());out.put("count",stack.stackSize);out.put("name",stack.getDisplayName());
        if(stack.hasTagCompound()) out.put("nbt",stack.getTagCompound().toString());return out;
    }
}
