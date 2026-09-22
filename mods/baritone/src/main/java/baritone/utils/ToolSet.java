// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.utils;

import baritone.Baritone;
import baritone.compat.IBlockState;
import net.minecraft.block.Block;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.potion.Potion;

/** Snapshot of native hotbar stacks, retaining metadata/NBT and Forge dig-speed hooks. */
public final class ToolSet {
    private final ItemStack[] stacks=new ItemStack[9];
    private final boolean[] eligible=new boolean[9];
    private final int selected;
    private final double amplifier;
    private final java.util.Map<IBlockState.StateKey,Double> breakStrengthCache=new java.util.HashMap<>();
    public ToolSet(ItemStack[] hotbar,int selected,double amplifier){
        if(hotbar.length!=9||selected<0||selected>8)throw new IllegalArgumentException("nine hotbar slots and selected index required");
        for(int i=0;i<9;i++){stacks[i]=hotbar[i]==null?null:hotbar[i].copy();eligible[i]=baritone.gtnh.ReferenceToolPolicy.eligible(stacks[i]);}this.selected=selected;this.amplifier=amplifier;
    }
    public ToolSet(EntityPlayerSP player){
        for(int i=0;i<9;i++){ItemStack s=player.inventory.getStackInSlot(i);stacks[i]=s==null?null:s.copy();eligible[i]=baritone.gtnh.ReferenceToolPolicy.eligible(stacks[i]);}
        selected=player.inventory.currentItem;
        double speed=1;
        if(Baritone.settings().considerPotionEffects.value){
            if(player.isPotionActive(Potion.digSpeed))speed*=1+(player.getActivePotionEffect(Potion.digSpeed).getAmplifier()+1)*0.2;
            // Native 1.7 fatigue differs from 1.12's exponential penalty.
            if(player.isPotionActive(Potion.digSlowdown))speed*=Math.max(0,1-(player.getActivePotionEffect(Potion.digSlowdown).getAmplifier()+1)*0.2);
        }
        amplifier=speed;
    }
    public double getStrVsBlock(IBlockState state){
        return breakStrengthCache.computeIfAbsent(state.key(),ignored->bestStrength(state));
    }
    private double bestStrength(IBlockState state){
        int slot=Baritone.settings().autoTool.value?getBestSlot(state,false):selected;
        if(!eligible[slot])return 0;
        double speed=calculateSpeedVsBlock(stacks[slot],state)*amplifier;
        return Baritone.settings().blocksToAvoidBreaking.value.contains(state.getBlock())?speed*Baritone.settings().avoidBreakingMultiplier.value:speed;
    }
    public int getBestSlot(IBlockState state,boolean preferSilkTouch){
        int best=selected,lowestCost=Integer.MAX_VALUE;double highestSpeed=Double.NEGATIVE_INFINITY;boolean bestSilk=false;
        for(int i=0;i<9;i++){
            if(!eligible[i])continue;
            ItemStack stack=stacks[i];
            if(stack!=null&&(!Baritone.settings().useSwordToMine.value&&stack.getItem() instanceof ItemSword||Baritone.settings().itemSaver.value&&stack.getMaxDamage()>1&&stack.getItemDamage()+Baritone.settings().itemSaverThreshold.value>=stack.getMaxDamage()))continue;
            double speed=calculateSpeedVsBlock(stack,state);
            boolean silk=stack!=null&&EnchantmentHelper.getEnchantmentLevel(Enchantment.silkTouch.effectId,stack)>0;
            String tool=state.getBlock().getHarvestTool(state.meta);
            int cost=stack==null||tool==null?-1:Math.max(-1,stack.getItem().getHarvestLevel(stack,tool));
            if(speed>highestSpeed||speed==highestSpeed&&(cost<lowestCost&&(silk||!bestSilk)||preferSilkTouch&&!bestSilk&&silk)){
                highestSpeed=speed;best=i;lowestCost=cost;bestSilk=silk;
            }
        }
        return best;
    }
    public boolean canHarvest(IBlockState state){
        int slot=Baritone.settings().autoTool.value?getBestSlot(state,false):selected;
        return eligible[slot]&&harvestable(stacks[slot],state);
    }
    private static boolean harvestable(ItemStack stack,IBlockState state){
        if(state.getMaterial().isToolNotRequired())return true;
        if(stack==null)return false;
        // Forge 1.7's player harvest path falls back to the stack callback when
        // a tool does not advertise a numeric harvest level. Generated mod tools
        // can implement that callback without implementing getHarvestLevel.
        String tool=state.getBlock().getHarvestTool(state.meta);
        int level=tool==null?-1:stack.getItem().getHarvestLevel(stack,tool);
        return level<0?stack.func_150998_b(state.getBlock()):level>=state.getBlock().getHarvestLevel(state.meta);
    }
    public static double calculateSpeedVsBlock(ItemStack stack,IBlockState state){
        // The game's own answer when it has given one: a pack rewrites break speed in events this formula cannot see.
        Double game=baritone.gtnh.ReferenceToolPolicy.strength(stack,state,state.hasAccess());
        if(game!=null) return game;
        float hardness;
        try{hardness=state.getBlock().getBlockHardness(null,state.x,state.y,state.z);}
        catch(RuntimeException unsupported){return 0;} // Position-dependent hardness needs a captured native cost; never invent one.
        if(hardness<0)return -1;
        float speed=stack==null?1:stack.getItem().getDigSpeed(stack,state.getBlock(),state.meta);
        if(stack!=null&&speed>1){int e=EnchantmentHelper.getEnchantmentLevel(Enchantment.efficiency.effectId,stack);if(e>0)speed+=e*e+1;}
        boolean harvest=harvestable(stack,state);
        return speed/hardness/(harvest?30:100);
    }
}
