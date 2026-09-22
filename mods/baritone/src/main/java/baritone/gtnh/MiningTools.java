// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh;

import baritone.compat.BlockPos;
import java.util.*;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.world.World;

/** Tool estimates use the installed pack's harvest, NBT and break-speed hooks. */
final class MiningTools {
    record Choice(int slot,double ticks) {}
    static double breakTicks(double strength) {
        return Double.isNaN(strength)||strength<=0?Double.POSITIVE_INFINITY:Math.max(1,Math.ceil(1/strength));
    }
    private static Class<?> gregtechType;
    private static boolean gregtechResolved;
    private static Class<?> gregtechType() {
        if(!gregtechResolved){gregtechResolved=true;try{gregtechType=Class.forName("gregtech.api.items.MetaGeneratedTool");}catch(ClassNotFoundException|LinkageError ignored){}}
        return gregtechType;
    }
    /** Why a stack must not be swung at all: it is empty, one use from breaking, or an uncharged tool. Nothing here guesses
     *  what a tool does by its class. Whether it can harvest a block and how fast is the game's own answer (best, below), and
     *  what one swing actually broke is measured by the job that swings it (a 3x3 hammer, a vein miner). */
    static String rejected(ItemStack stack) {
        if(stack==null) return null;
        if(stack.stackSize<=0) return "empty_stack";
        if(stack.hasTagCompound() && stack.getTagCompound().hasKey("InfiTool")) {
            var nbt=stack.getTagCompound().getCompoundTag("InfiTool");
            if(nbt.getBoolean("Broken")) return "broken_tool";
            if(nbt.hasKey("TotalDurability") && nbt.getInteger("TotalDurability")-nbt.getInteger("Damage")<=1) return "durability_reserve";
        }
        if(stack.isItemStackDamageable() && stack.getMaxDamage()-stack.getItemDamage()<=1) return "durability_reserve";
        if(stack.getItem() instanceof ItemSword) return "sword_reserved";
        Class<?> gt=gregtechType();
        return gt!=null&&gt.isInstance(stack.getItem())?gregtechRejected(stack,gt):null;
    }
    /** GregTech keeps durability and charge in its own NBT, where isItemStackDamageable cannot see them. */
    private static String gregtechRejected(ItemStack stack,Class<?> gt) {
        try {
            Object tool=stack.getItem(),stats=gt.getMethod("getToolStats",ItemStack.class).invoke(tool,stack);
            if(!Boolean.TRUE.equals(gt.getMethod("isItemStackUsable",ItemStack.class).invoke(tool,stack)))return "gregtech_tool_unusable_or_uncharged";
            long maximum=((Number)gt.getMethod("getToolMaxDamage",ItemStack.class).invoke(null,stack)).longValue();
            long damage=((Number)gt.getMethod("getToolDamage",ItemStack.class).invoke(null,stack)).longValue();
            int cost=stats==null?1:((Number)stats.getClass().getMethod("getToolDamagePerBlockBreak").invoke(stats)).intValue();
            return maximum>0&&maximum-damage<=Math.max(1,cost)?"durability_reserve":null;
        }catch(ReflectiveOperationException|LinkageError error){return null;} // not a GT API we know: let the game's harvest answer decide
    }
    static Choice best(World world,BlockPos p,List<Map<String,Object>> observations) {
        Minecraft mc=Minecraft.getMinecraft();Block block=world.getBlock(p.getX(),p.getY(),p.getZ());int meta=world.getBlockMetadata(p.getX(),p.getY(),p.getZ());
        Choice best=null;int selected=mc.thePlayer.inventory.currentItem;
        ItemStack original=mc.thePlayer.inventory.mainInventory[selected];
        boolean emptySeen=false;
        // Only a synchronous game-thread query: restore in finally, with no packet or tick between probes.
        try {
            for(int order=0;order<36;order++) {
                int slot=order==0?selected:order<=selected?order-1:order;
                ItemStack candidate=slot==selected?original:mc.thePlayer.inventory.mainInventory[slot];
                String reason=rejected(candidate);double ticks=Double.POSITIVE_INFINITY;
                if(candidate==null && emptySeen && observations==null) continue;
                emptySeen|=candidate==null;
                if(reason==null) {
                    mc.thePlayer.inventory.mainInventory[selected]=candidate==null?null:candidate.copy();
                    if(!block.canHarvestBlock(mc.thePlayer,meta)) reason="cannot_harvest";
                    else {
                        double strength=block.getPlayerRelativeBlockHardness(mc.thePlayer,world,p.getX(),p.getY(),p.getZ());
                        // Zero-hardness plants return +infinity and break on the first hit.
                        ticks=breakTicks(strength);
                        if(!Double.isFinite(ticks)) reason="cannot_break";
                    }
                }
                if(reason==null && (best==null || ticks<best.ticks())) best=new Choice(slot,ticks);
                if(observations!=null) {
                    Map<String,Object> out=new LinkedHashMap<>();out.put("slot",slot);out.put("stack",InventorySelection.describe(candidate));
                    out.put("eligible",reason==null);out.put("reason",reason);out.put("estimatedTicks",Double.isFinite(ticks)?ticks:null);observations.add(out);
                }
            }
        } finally {mc.thePlayer.inventory.mainInventory[selected]=original;}
        return best;
    }
    static Map<String,Object> inspect(World world,BlockPos p) {
        if(!ForgeSnapshot.loaded(world,p.getX(),p.getY(),p.getZ())) throw new IllegalArgumentException("target not loaded");
        List<Map<String,Object>> tools=new ArrayList<>();Choice best=best(world,p,tools);
        Map<String,Object> out=new LinkedHashMap<>();out.put("target",List.of(p.getX(),p.getY(),p.getZ()));out.put("tools",tools);
        out.put("bestSlot",best==null?null:best.slot());out.put("estimatedTicks",best==null?null:best.ticks());
        out.put("estimateOnly",true);return out;
    }
}
