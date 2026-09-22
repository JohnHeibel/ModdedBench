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
    /** Tools the running mining job measured breaking nothing the game said they break. The job fills and clears it.
     *  A tool is its item and, when the item has subtypes (one GregTech item is every GregTech tool), its meta: durability
     *  changes with every swing and must not make the same tool look new. */
    static final Set<String> ineffective=new HashSet<>();
    static String toolKind(ItemStack stack){return stack==null?"hand":net.minecraft.item.Item.itemRegistry.getNameForObject(stack.getItem())+(stack.getHasSubtypes()?":"+stack.getItemDamage():"");}
    static String rejected(ItemStack stack) {
        if(stack==null) return null;
        if(ineffective.contains(toolKind(stack))) return "measured_breaking_nothing";
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
    // The game's own answer to "how much of this block does one tick with this stack in hand break", as vanilla computes it:
    // the stack's dig speed and efficiency, then Forge's BreakSpeed event and harvest check, where a pack rewrites both (a
    // vanilla stone pickaxe here is vetoed by the event and breaks nothing). The player's momentary state (in water, in the
    // air, potions) is left out, so a plan does not change with a jump. Asked on the game thread only; the path search runs
    // on its own thread, reads the answers kept here, and queues the ones it lacks for the next game tick.
    private record Key(net.minecraft.item.Item item,int damage,int nbt,Block block,int meta,long pos) {}
    private static final Map<Key,Double> answers=new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<Key,int[]> asked=new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<Key,ItemStack> askedStacks=new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile Thread gameThread;
    private static Key key(ItemStack stack,Block block,int meta,int x,int y,int z,boolean placed) {
        // A block with a tile entity may keep its identity there (a GregTech ore's material): its answer is per position.
        long pos=placed&&block.hasTileEntity(meta)?(((long)x&0x3FFFFFF)<<38)|(((long)y&0xFFF)<<26)|((long)z&0x3FFFFFF):Long.MIN_VALUE;
        return stack==null?new Key(null,0,0,block,meta,pos):new Key(stack.getItem(),stack.getItemDamage(),stack.hasTagCompound()?stack.getTagCompound().hashCode():0,block,meta,pos);
    }
    /** The game's answer, or null when there is none yet (off the game thread, not asked before, or no game at all). */
    static Double strength(ItemStack stack,Block block,int meta,int x,int y,int z,boolean placed) {
        if(gameThread==null) return null;
        Key key=key(stack,block,meta,x,y,z,placed);
        if(Thread.currentThread()==gameThread) return answers.computeIfAbsent(key,k->ask(stack,block,meta,x,y,z,placed));
        Double known=answers.get(key);
        if(known==null&&asked.size()<256&&asked.putIfAbsent(key,new int[]{x,y,z,placed?1:0})==null&&stack!=null)askedStacks.put(key,stack.copy());
        return known;
    }
    /** Once a game tick: answer what the path search asked since the last one. */
    static void answer() {
        gameThread=Thread.currentThread();
        if(answers.size()>8192) answers.clear();
        int n=0;
        for(var it=asked.entrySet().iterator();it.hasNext()&&n++<32;) {
            var e=it.next();it.remove();var k=e.getKey();var p=e.getValue();
            answers.putIfAbsent(k,ask(askedStacks.remove(k),k.block(),k.meta(),p[0],p[1],p[2],p[3]==1));
        }
    }
    private static double ask(ItemStack stack,Block block,int meta,int x,int y,int z,boolean placed) {
        Minecraft mc=Minecraft.getMinecraft();var player=mc.thePlayer;
        if(player==null||mc.theWorld==null) return 0;
        float hardness;
        try{hardness=block.getBlockHardness(placed?mc.theWorld:null,x,y,z);}catch(RuntimeException positionDependent){return 0;}
        if(hardness<0) return -1;
        int slot=player.inventory.currentItem;ItemStack original=player.inventory.mainInventory[slot];
        try {
            player.inventory.mainInventory[slot]=stack==null?null:stack.copy();
            float speed=stack==null?1:stack.getItem().getDigSpeed(stack,block,meta);
            if(speed>1&&stack!=null){int e=net.minecraft.enchantment.EnchantmentHelper.getEfficiencyModifier(player);if(e>0)speed+=e*e+1;}
            speed=net.minecraftforge.event.ForgeEventFactory.getBreakSpeed(player,block,meta,speed,x,y,z);
            if(!(speed>0)) return 0;
            boolean harvest=net.minecraftforge.common.ForgeHooks.canHarvestBlock(block,player,meta);
            return hardness==0?Double.POSITIVE_INFINITY:speed/hardness/(harvest?30:100);
        } catch(RuntimeException|LinkageError failed) {return 0;} // a handler that cannot answer for a stack in hand: never invent a speed
        finally {player.inventory.mainInventory[slot]=original;}
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
