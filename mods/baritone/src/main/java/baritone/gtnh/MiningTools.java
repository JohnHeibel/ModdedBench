// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh;

import baritone.compat.BlockPos;
import java.util.*;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.world.World;

/** Tool estimates use the installed pack's harvest, NBT and break-speed hooks. */
final class MiningTools {
    record Choice(int slot,double ticks) {}
    static double breakTicks(double strength) {
        return Double.isNaN(strength)||strength<=0?Double.POSITIVE_INFINITY:Math.max(1,Math.ceil(1/strength));
    }
    private static final Set<String> TINKER_SINGLE=Set.of("tconstruct.items.tools.Pickaxe","tconstruct.items.tools.Shovel","tconstruct.items.tools.Hatchet");
    private static final Set<String> GT_SINGLE=Set.of("gregtech.common.tools.ToolDrillLV","gregtech.common.tools.ToolDrillMV","gregtech.common.tools.ToolDrillHV");
    private static Class<?> gregtechType;
    private static boolean gregtechResolved;
    private static Class<?> gregtechType() {
        if(!gregtechResolved){gregtechResolved=true;try{gregtechType=Class.forName("gregtech.api.items.MetaGeneratedTool");}catch(ClassNotFoundException|LinkageError ignored){}}
        return gregtechType;
    }
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
        if(TINKER_SINGLE.contains(stack.getItem().getClass().getName())) return null;
        Class<?> gt=gregtechType();
        if(gt!=null&&gt.isInstance(stack.getItem()))return gregtechRejected(stack,gt);
        try {
            if(stack.getItem().getClass().getMethod("onBlockStartBreak",ItemStack.class,int.class,int.class,int.class,EntityPlayer.class).getDeclaringClass()==Item.class)
                return null;
        } catch(ReflectiveOperationException ignored) { }
        return "unverified_break_behavior";
    }
    private static String gregtechRejected(ItemStack stack,Class<?> gt) {
        try {
            Object tool=stack.getItem(),stats=gt.getMethod("getToolStats",ItemStack.class).invoke(tool,stack);
            if(stats==null||!GT_SINGLE.contains(stats.getClass().getName()))return "unverified_gregtech_tool_behavior";
            // GT's generated item dispatches through per-stack IToolStats. An
            // item registry ID (or its broad wrench interface) cannot identify
            // behavior. These three stat implementations retain the native
            // single-block callback; area tools need their own bounded adapter.
            if(tool.getClass().getMethod("onBlockStartBreak",ItemStack.class,int.class,int.class,int.class,EntityPlayer.class).getDeclaringClass()!=gt)return "overridden_gregtech_break_behavior";
            if(!Boolean.TRUE.equals(stats.getClass().getMethod("isMiningTool").invoke(stats)))return "not_mining_tool";
            for(String flag:List.of("isChainsaw","isWrench","isCrowbar","isGrafter","isWeapon"))if(Boolean.TRUE.equals(stats.getClass().getMethod(flag).invoke(stats)))return "gregtech_special_tool_behavior";
            if(((Number)gt.getMethod("getToolMaxMode",ItemStack.class).invoke(tool,stack)).intValue()>1||((Number)gt.getMethod("getToolMode",ItemStack.class).invoke(null,stack)).intValue()!=0)return "unverified_gregtech_tool_mode";
            if(!Boolean.TRUE.equals(gt.getMethod("isItemStackUsable",ItemStack.class).invoke(tool,stack)))return "gregtech_tool_unusable_or_uncharged";
            long maximum=((Number)gt.getMethod("getToolMaxDamage",ItemStack.class).invoke(null,stack)).longValue();
            long damage=((Number)gt.getMethod("getToolDamage",ItemStack.class).invoke(null,stack)).longValue();
            int cost=((Number)stats.getClass().getMethod("getToolDamagePerBlockBreak").invoke(stats)).intValue();
            if(maximum-damage<=Math.max(1,cost))return "durability_reserve";
            return null;
        }catch(ReflectiveOperationException|LinkageError error){return "gregtech_tool_api_unavailable";}
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
