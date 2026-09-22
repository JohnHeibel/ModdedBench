/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Modified by the ModdedBench project (2026) for Minecraft 1.7.10 / GT New Horizons.
 * The original file and its SHA-256 are recorded in META-INF/modbench/UPSTREAM_SOURCES.json.
 */

package baritone.behavior;
import baritone.compat.LegacyInventorySwap;

import baritone.Baritone;
import baritone.api.event.events.TickEvent;
import baritone.api.utils.Helper;
import baritone.gtnh.ReferenceToolPolicy;
import net.minecraft.block.Block;
import baritone.compat.IBlockState;
import net.minecraft.client.entity.EntityPlayerSP;
import baritone.compat.Blocks;

import net.minecraft.item.*;
import baritone.compat.EnumFacing;


import java.util.ArrayList;
import java.util.OptionalInt;
import java.util.Random;
import java.util.function.Predicate;

public final class InventoryBehavior extends Behavior implements Helper {

    @FunctionalInterface public interface PlacementTarget { IBlockState apply(int x, int y, int z); }
    public PlacementTarget placementTarget = (x, y, z) -> null;
    public Predicate<ItemStack> throwawayFilter=stack->true;
    private static boolean empty(ItemStack stack) { return stack == null || stack.stackSize <= 0; }
    int ticksSinceLastInventoryMove;
    private ItemStack requestedStack;
    private boolean selectionPending;
    private baritone.compat.LegacyInventorySwap swap;
    public boolean selectionPending() {return selectionPending;}
    public boolean hasPendingMove() {return swap!=null||lastTickRequestedMove!=null||selectionPending;}
    public boolean swapping(){return swap!=null;}
    public void resetRequests() {if(swap!=null){var old=swap;swap=null;old.close();}lastTickRequestedMove=null;requestedStack=null;selectionPending=false;}
    int[] lastTickRequestedMove; // not everything asks every tick, so remember the request while coming to a halt

    public InventoryBehavior(Baritone baritone) {
        super(baritone);
    }

    @Override
    public void onTick(TickEvent event) {
        ReferenceToolPolicy.answer();
        if(swap!=null){
            if(swap.tick()!=LegacyInventorySwap.Outcome.WAITING){swap=null;lastTickRequestedMove=null;requestedStack=null;selectionPending=false;}
            else {baritone.getInventoryPauserProcess().stationaryForInventoryMove();return;}
        }
        if (!Baritone.settings().allowInventory.value) {
            return;
        }
        if (event.getType() == TickEvent.Type.OUT) {
            return;
        }
        if (ctx.player().openContainer != ctx.player().inventoryContainer) {
            // we have a crafting table or a chest or something open
            return;
        }
        ticksSinceLastInventoryMove++;
        if (firstValidThrowaway() >= 9) { // aka there are none on the hotbar, but there are some in main inventory
            requestSwapWithHotBar(firstValidThrowaway(), 8);
        }
        if (lastTickRequestedMove != null) {
            logDebug("Remembering to move " + lastTickRequestedMove[0] + " " + lastTickRequestedMove[1] + " from a previous tick");
            if (ItemStack.areItemStacksEqual(requestedStack, ctx.player().inventory.getStackInSlot(lastTickRequestedMove[0])))
                requestSwapWithHotBar(lastTickRequestedMove[0], lastTickRequestedMove[1]);
            else lastTickRequestedMove = null;
        }
    }

    public boolean attemptToPutOnHotbar(int inMainInvy, Predicate<Integer> disallowedHotbar) {
        OptionalInt destination = getTempHotbarSlot(disallowedHotbar);
        if (destination.isPresent()) {
            if (!requestSwapWithHotBar(inMainInvy, destination.getAsInt())) {
                return false;
            }
        }
        return destination.isPresent();
    }

    public OptionalInt getTempHotbarSlot(Predicate<Integer> disallowedHotbar) {
        // we're using 0 and 8 for pickaxe and throwaway
        ArrayList<Integer> candidates = new ArrayList<>();
        for (int i = 1; i < 8; i++) {
            if (empty(ctx.player().inventory.mainInventory[i]) && !disallowedHotbar.test(i)) {
                candidates.add(i);
            }
        }
        if (candidates.isEmpty()) {
            for (int i = 1; i < 8; i++) {
                if (!disallowedHotbar.test(i)) {
                    candidates.add(i);
                }
            }
        }
        if (candidates.isEmpty()) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(candidates.get(new Random().nextInt(candidates.size())));
    }

    private boolean requestSwapWithHotBar(int inInventory, int inHotbar) {
        if(swap!=null)return false;
        lastTickRequestedMove = new int[]{inInventory, inHotbar};
        ItemStack requested = ctx.player().inventory.getStackInSlot(inInventory);
        requestedStack = requested == null ? null : requested.copy();
        if (ticksSinceLastInventoryMove < Baritone.settings().ticksBetweenInventoryMoves.value) {
            logDebug("Inventory move requested but delaying " + ticksSinceLastInventoryMove + " " + Baritone.settings().ticksBetweenInventoryMoves.value);
            return false;
        }
        if (Baritone.settings().inventoryMoveOnlyIfStationary.value && !baritone.getInventoryPauserProcess().stationaryForInventoryMove()) {
            logDebug("Inventory move requested but delaying until stationary");
            return false;
        }
        if (ctx.player().inventory.getItemStack() != null) return false;
        swap=new baritone.compat.LegacyInventorySwap(baritone,inInventory,inHotbar);
        ticksSinceLastInventoryMove = 0;
        lastTickRequestedMove = null;
        return false; // Completion is published by onTick after native acknowledgement.
    }

    private int firstValidThrowaway() { // TODO offhand idk
        ItemStack[] invy = ctx.player().inventory.mainInventory;
        for (int i = 0; i < invy.length; i++) {
            if (!empty(invy[i]) && throwawayFilter.test(invy[i]) && Baritone.settings().acceptableThrowawayItems.value.contains(invy[i].getItem())) {
                return i;
            }
        }
        return -1;
    }

    /** ModdedBench: hold inventory slot `slot`, swapping one from the main inventory into an empty hotbar slot, else the held one. */
    public void select(int slot) {
        ItemStack[] inv = ctx.player().inventory.mainInventory;
        if (slot < 9) {
            ctx.player().inventory.currentItem = slot;
            return;
        }
        int hotbar = ctx.player().inventory.currentItem;
        for (int i = 0; i < 9; i++) if (empty(inv[i])) {hotbar = i; break;}
        requestSwapWithHotBar(slot, hotbar);
    }

    public boolean hasGenericThrowaway() {
        for (Item item : Baritone.settings().acceptableThrowawayItems.value) {
            if (throwaway(false, stack -> item.equals(stack.getItem()) && throwawayFilter.test(stack))) {
                return true;
            }
        }
        return false;
    }

    /** The execution guard uses the same item policy as source support selection. */
    public boolean isGenericThrowaway(ItemStack stack) {
        return !empty(stack) && Baritone.settings().acceptableThrowawayItems.value.contains(stack.getItem())
                && throwawayFilter.test(stack);
    }

    public boolean selectThrowawayForLocation(boolean select, int x, int y, int z) {
        IBlockState maybe = placementTarget.apply(x, y, z);
        if (maybe != null && throwaway(select, stack -> stack.getItem() instanceof ItemBlock
                && Block.getBlockFromItem(stack.getItem()) == maybe.getBlock()
                && dev.modbench.api.ControlRegistry.placement().initialMetadata(stack) == maybe.meta
                && builderMaterial(stack,maybe))) return true;
        if (maybe != null && throwaway(select, stack -> stack.getItem() instanceof ItemBlock
                && Block.getBlockFromItem(stack.getItem()) == maybe.getBlock() && builderMaterial(stack,maybe))) return true;
        for (Item item : Baritone.settings().acceptableThrowawayItems.value) {
            if (throwaway(select, stack -> item.equals(stack.getItem()) && throwawayFilter.test(stack))) {
                return true;
            }
        }
        return false;
    }

    public boolean throwaway(boolean select, Predicate<? super ItemStack> desired) {
        return throwaway(select, desired, Baritone.settings().allowInventory.value);
    }
    private boolean builderMaterial(ItemStack stack,IBlockState target){
        return baritone.getBuilderProcess().stateValidator.valid(IBlockState.of(Block.getBlockFromItem(stack.getItem()),dev.modbench.api.ControlRegistry.placement().initialMetadata(stack)).withPlacementItem(stack),target,true);
    }

    public boolean throwaway(boolean select, Predicate<? super ItemStack> desired, boolean allowInventory) {
        if (select) selectionPending=false;
        EntityPlayerSP p = ctx.player();
        ItemStack[] inv = p.inventory.mainInventory;
        for (int i = 0; i < 9; i++) {
            ItemStack item = inv[i];
            // this usage of settings() is okay because it's only called once during pathing
            // (while creating the CalculationContext at the very beginning)
            // and then it's called during execution
            // since this function is never called during cost calculation, we don't need to migrate
            // acceptableThrowawayItems to the CalculationContext
            if (!empty(item) && desired.test(item)) {
                if (select) {
                    p.inventory.currentItem = i;
                }
                return true;
            }
        }
        if (allowInventory) {
            for (int i = 9; i < 36; i++) {
                if (!empty(inv[i]) && desired.test(inv[i])) {
                    if (select) {
                        // Never acknowledge selection while a deferred swap still holds a different item.
                        if (!requestSwapWithHotBar(i, 7)) {selectionPending=true;return true;}
                        if (empty(inv[7]) || !desired.test(inv[7])) {selectionPending=true;return true;}
                        p.inventory.currentItem = 7;
                    }
                    return true;
                }
            }
        }

        return false;
    }
}
