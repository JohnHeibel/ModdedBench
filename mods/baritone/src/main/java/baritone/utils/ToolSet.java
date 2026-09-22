// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.utils;

import baritone.Baritone;
import baritone.compat.IBlockState;
import baritone.gtnh.ReferenceToolPolicy;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;

/** Snapshot of the stacks the player may swing (the whole inventory when allowInventory, else the hotbar), with metadata and
 *  NBT. Every answer about them is the game's own and the choice is the one pick of ReferenceToolPolicy. */
public final class ToolSet {
    /** Who answers what a stack does to a block: the game (ReferenceToolPolicy.answers), or a test standing in for it. */
    public interface Answers {ReferenceToolPolicy.Answer[] of(ItemStack[] stacks,IBlockState state);}
    private record Pick(int slot,double strength,boolean harvest) {}
    private record Cell(IBlockState.StateKey state,long cell) {}
    private final ItemStack[] stacks;
    private final boolean[] eligible;
    private final int selected,forced;
    private final double amplifier;
    private final Answers game;
    private final java.util.Map<Cell,Pick> picks=new java.util.concurrent.ConcurrentHashMap<>();
    public ToolSet(ItemStack[] stacks,int selected,double amplifier,Answers game){
        if(selected<0||selected>=stacks.length)throw new IllegalArgumentException("selected slot outside the stacks");
        this.stacks=new ItemStack[stacks.length];eligible=new boolean[stacks.length];
        for(int i=0;i<stacks.length;i++){this.stacks[i]=stacks[i]==null?null:stacks[i].copy();eligible[i]=ReferenceToolPolicy.eligible(this.stacks[i]);}
        this.selected=selected;this.amplifier=amplifier;this.game=game;forced=-1;
    }
    public ToolSet(EntityPlayerSP player){
        int size=Baritone.settings().allowInventory.value?36:9;stacks=new ItemStack[size];eligible=new boolean[size];
        for(int i=0;i<size;i++){ItemStack s=player.inventory.getStackInSlot(i);stacks[i]=s==null?null:s.copy();eligible[i]=ReferenceToolPolicy.eligible(stacks[i]);}
        selected=player.inventory.currentItem;game=ReferenceToolPolicy::answers;
        forced=ReferenceToolPolicy.forced(stacks,selected);
        double speed=1;
        if(Baritone.settings().considerPotionEffects.value){
            if(player.isPotionActive(Potion.digSpeed))speed*=1+(player.getActivePotionEffect(Potion.digSpeed).getAmplifier()+1)*0.2;
            // Native 1.7 fatigue differs from 1.12's exponential penalty.
            if(player.isPotionActive(Potion.digSlowdown))speed*=Math.max(0,1-(player.getActivePotionEffect(Potion.digSlowdown).getAmplifier()+1)*0.2);
        }
        amplifier=speed;
    }
    /** A forced tool (a mining job's toolSlot) is swung whatever the harness thinks of it; without autoTool the held stack is, if it is eligible. */
    private Pick pick(IBlockState state){
        return picks.computeIfAbsent(new Cell(state.key(),ReferenceToolPolicy.cell(state)),ignored->{
            var answers=game.of(stacks,state);
            int slot=forced>=0?forced:!Baritone.settings().autoTool.value?(eligible[selected]?selected:-1):ReferenceToolPolicy.pick(answers,eligible,selected);
            var answer=slot<0?null:answers[slot];
            return new Pick(slot<0?selected:slot,answer==null?0:answer.strength(),answer!=null&&answer.harvest());
        });
    }
    public double getStrVsBlock(IBlockState state){
        double speed=pick(state).strength()*amplifier;
        return Baritone.settings().blocksToAvoidBreaking.value.contains(state.getBlock())?speed*Baritone.settings().avoidBreakingMultiplier.value:speed;
    }
    /** The inventory slot to swing at this block: 0..8 is the hotbar, 9..35 needs a swap first. */
    public int getBestSlot(IBlockState state){return pick(state).slot();}
    public boolean canHarvest(IBlockState state){return pick(state).harvest();}
}
