// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh;

import baritone.Baritone;
import baritone.compat.BlockPos;
import java.util.*;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.world.World;

/** Tool estimates use the installed pack's harvest, NBT and break-speed hooks. */
final class MiningTools {
    static double breakTicks(double strength) {
        return Double.isNaN(strength)||strength<=0?Double.POSITIVE_INFINITY:Math.max(1,Math.ceil(1/strength));
    }
    private static Class<?> gregtechType;
    private static boolean gregtechResolved;
    private static Class<?> gregtechType() {
        if(!gregtechResolved){gregtechResolved=true;try{gregtechType=Class.forName("gregtech.api.items.MetaGeneratedTool");}catch(ClassNotFoundException|LinkageError ignored){}}
        return gregtechType;
    }
    /** Tools the running mining job measured breaking nothing the game said they break. The job fills and clears it.
     *  A tool is its item and, when the item has subtypes (one GregTech item is every GregTech tool), its meta: durability
     *  changes with every swing and must not make the same tool look new. */
    static final Set<String> ineffective=new HashSet<>();
    static String toolKind(ItemStack stack){return stack==null?"hand":net.minecraft.item.Item.itemRegistry.getNameForObject(stack.getItem())+(stack.getHasSubtypes()?":"+stack.getItemDamage():"");}
    /** The model's toolsToAvoid names this stack's kind, or its item for every subtype. */
    static boolean avoided(ItemStack stack){
        var avoid=Baritone.settings().toolsToAvoid.value;
        return stack!=null&&!avoid.isEmpty()&&(avoid.contains(toolKind(stack))||avoid.contains(net.minecraft.item.Item.itemRegistry.getNameForObject(stack.getItem())));
    }
    /** Why a stack must not be swung at all: the model avoids it, it measured breaking nothing, it is one use from breaking,
     *  or an uncharged tool; swords and itemSaver follow their settings. Nothing here guesses what a tool does by its class.
     *  Whether it can harvest a block and how fast is the game's own answer (below), and what one swing actually broke is
     *  measured by the job that swings it (a 3x3 hammer, a vein miner). */
    static String rejected(ItemStack stack) {
        if(stack==null) return null;
        if(avoided(stack)) return "avoided_by_toolsToAvoid";
        if(ineffective.contains(toolKind(stack))) return "measured_breaking_nothing";
        if(stack.stackSize<=0) return "empty_stack";
        if(stack.hasTagCompound() && stack.getTagCompound().hasKey("InfiTool")) {
            var nbt=stack.getTagCompound().getCompoundTag("InfiTool");
            if(nbt.getBoolean("Broken")) return "broken_tool";
            if(nbt.hasKey("TotalDurability") && nbt.getInteger("TotalDurability")-nbt.getInteger("Damage")<=1) return "durability_reserve";
        }
        if(stack.isItemStackDamageable() && stack.getMaxDamage()-stack.getItemDamage()<=1) return "durability_reserve";
        var settings=Baritone.settings();
        if(settings.itemSaver.value && stack.isItemStackDamageable() && stack.getItemDamage()+settings.itemSaverThreshold.value>=stack.getMaxDamage()) return "itemSaver_threshold";
        if(!settings.useSwordToMine.value && stack.getItem() instanceof ItemSword) return "sword_kept_by_useSwordToMine";
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
    // The game's own answer to "how much of this block does one tick with this stack in hand break, and does it drop", as
    // vanilla computes it: the stack's dig speed and efficiency, then Forge's BreakSpeed event and harvest check, where a pack
    // rewrites both (a vanilla stone pickaxe here is vetoed by the event and breaks nothing). The player's momentary state (in
    // water, in the air, potions) is left out, so a plan does not change with a jump. Asked on the game thread only; the path
    // search runs on its own thread, queues what it lacks and waits for the next game tick to answer it.
    private record Key(net.minecraft.item.Item item,int damage,int nbt,Block block,int meta,long pos) {}
    private record Ask(ItemStack stack,int x,int y,int z,boolean placed) {}
    private static final Map<Key,ReferenceToolPolicy.Answer> answers=new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<Key,Ask> asked=new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile Thread gameThread;
    /** A block with a tile entity may keep its identity there (a GregTech ore's material): its answer is per position. */
    static long cell(Block block,int meta,int x,int y,int z,boolean placed) {
        return placed&&block.hasTileEntity(meta)?(((long)x&0x3FFFFFF)<<38)|(((long)y&0xFFF)<<26)|((long)z&0x3FFFFFF):Long.MIN_VALUE;
    }
    private static Key key(ItemStack stack,Block block,int meta,long cell) {
        return stack==null?new Key(null,0,0,block,meta,cell):new Key(stack.getItem(),stack.getItemDamage(),stack.hasTagCompound()?stack.getTagCompound().hashCode():0,block,meta,cell);
    }
    /** The game's answer for each stack; an entry stays null only when the game did not answer within a few ticks (or there is no game). */
    static ReferenceToolPolicy.Answer[] answers(ItemStack[] stacks,Block block,int meta,int x,int y,int z,boolean placed) {
        var out=new ReferenceToolPolicy.Answer[stacks.length];
        if(gameThread==null) return out;
        long cell=cell(block,meta,x,y,z,placed);Key[] keys=new Key[stacks.length];boolean missing=false;
        for(int i=0;i<stacks.length;i++) {
            keys[i]=key(stacks[i],block,meta,cell);
            if(Thread.currentThread()==gameThread){ItemStack s=stacks[i];out[i]=answers.computeIfAbsent(keys[i],k->ask(s,block,meta,x,y,z,placed));continue;}
            out[i]=answers.get(keys[i]);
            if(out[i]==null){missing=true;if(asked.size()<1024)asked.putIfAbsent(keys[i],new Ask(stacks[i]==null?null:stacks[i].copy(),x,y,z,placed));}
        }
        for(long until=System.nanoTime()+200_000_000L;missing&&System.nanoTime()<until;) {
            synchronized(answers){try{answers.wait(10);}catch(InterruptedException stop){Thread.currentThread().interrupt();break;}}
            missing=false;
            for(int i=0;i<stacks.length;i++)if(out[i]==null&&(out[i]=answers.get(keys[i]))==null)missing=true;
        }
        return out;
    }
    /** Once a game tick: answer what the path search asked since the last one. */
    static void answer() {
        gameThread=Thread.currentThread();
        if(answers.size()>8192) answers.clear();
        if(asked.isEmpty()) return;
        int n=0;
        for(var it=asked.entrySet().iterator();it.hasNext()&&n++<512;) {
            var e=it.next();it.remove();var k=e.getKey();var a=e.getValue();
            answers.putIfAbsent(k,ask(a.stack(),k.block(),k.meta(),a.x(),a.y(),a.z(),a.placed()));
        }
        synchronized(answers){answers.notifyAll();}
    }
    private static ReferenceToolPolicy.Answer ask(ItemStack stack,Block block,int meta,int x,int y,int z,boolean placed) {
        Minecraft mc=Minecraft.getMinecraft();var player=mc.thePlayer;
        var none=new ReferenceToolPolicy.Answer(0,false);
        if(player==null||mc.theWorld==null) return none;
        float hardness;
        try{hardness=block.getBlockHardness(placed?mc.theWorld:null,x,y,z);}catch(RuntimeException positionDependent){return none;}
        if(hardness<0) return new ReferenceToolPolicy.Answer(-1,false);
        int slot=player.inventory.currentItem;ItemStack original=player.inventory.mainInventory[slot];
        try {
            player.inventory.mainInventory[slot]=stack==null?null:stack.copy();
            boolean harvest=net.minecraftforge.common.ForgeHooks.canHarvestBlock(block,player,meta);
            float speed=stack==null?1:stack.getItem().getDigSpeed(stack,block,meta);
            if(speed>1&&stack!=null){int e=net.minecraft.enchantment.EnchantmentHelper.getEfficiencyModifier(player);if(e>0)speed+=e*e+1;}
            speed=net.minecraftforge.event.ForgeEventFactory.getBreakSpeed(player,block,meta,speed,x,y,z);
            if(!(speed>0)) return new ReferenceToolPolicy.Answer(0,harvest);
            return new ReferenceToolPolicy.Answer(hardness==0?Double.POSITIVE_INFINITY:speed/hardness/(harvest?30:100),harvest);
        } catch(RuntimeException|LinkageError failed) {return none;} // a handler that cannot answer for a stack in hand: never invent a speed
        finally {player.inventory.mainInventory[slot]=original;}
    }
    /** The one tool choice, for the path search, the engine's swings and the one-block job alike: among the eligible stacks,
     *  one the game says harvests the block before one that does not, then the fastest; ties keep the earlier of `order`.
     *  -1 when none breaks it. */
    static int pick(ReferenceToolPolicy.Answer[] answers,boolean[] eligible,int[] order) {
        int best=-1;
        for(int i:order) {
            var a=answers[i];if(!eligible[i]||a==null||!(a.strength()>0))continue;
            var b=best<0?null:answers[best];
            if(b==null||a.harvest()&&!b.harvest()||a.harvest()==b.harvest()&&a.strength()>b.strength())best=i;
        }
        return best;
    }
    /** Slots in preference order: the held one first, then the rest in inventory order. */
    static int[] order(int selected,int size) {
        int[] out=new int[size];out[0]=selected;for(int i=0,j=1;i<size;i++)if(i!=selected)out[j++]=i;return out;
    }
    /** A forced slot (a mining job's toolSlot), else the choice above over the player's whole inventory; with `observations`,
     *  one row per stack. Game thread only. */
    static Map<String,Object> choose(World world,BlockPos p,List<Map<String,Object>> observations) {
        Minecraft mc=Minecraft.getMinecraft();Block block=world.getBlock(p.getX(),p.getY(),p.getZ());int meta=world.getBlockMetadata(p.getX(),p.getY(),p.getZ());
        ItemStack[] stacks=new ItemStack[36];boolean[] eligible=new boolean[36];String[] reasons=new String[36];
        for(int i=0;i<36;i++){stacks[i]=mc.thePlayer.inventory.mainInventory[i];reasons[i]=rejected(stacks[i]);eligible[i]=reasons[i]==null;}
        var answers=answers(stacks,block,meta,p.getX(),p.getY(),p.getZ(),true);
        int selected=mc.thePlayer.inventory.currentItem,forced=ReferenceToolPolicy.forced(stacks,selected),best=forced>=0?forced:pick(answers,eligible,order(selected,36));
        boolean emptySeen=false;
        if(observations!=null)for(int slot=0;slot<36;slot++) {
            if(stacks[slot]==null&&emptySeen&&slot!=best)continue; // one empty hand is enough
            emptySeen|=stacks[slot]==null;
            var a=answers[slot];double ticks=a==null?Double.POSITIVE_INFINITY:breakTicks(a.strength());
            Map<String,Object> out=new LinkedHashMap<>();out.put("slot",slot);out.put("stack",InventorySelection.describe(stacks[slot]));out.put("tool",toolKind(stacks[slot]));
            out.put("eligible",eligible[slot]);
            out.put("reason",reasons[slot]!=null?reasons[slot]:a==null?"game_did_not_answer":!(a.strength()>0)?"cannot_break":!a.harvest()?"breaks_without_harvest":null);
            out.put("strength",a==null?null:Double.isFinite(a.strength())?a.strength():"instant");out.put("harvestable",a!=null&&a.harvest());
            out.put("estimatedTicks",Double.isFinite(ticks)?ticks:null);
            out.put("avoided",avoided(stacks[slot]));out.put("measuredIneffective",stacks[slot]!=null&&ineffective.contains(toolKind(stacks[slot])));
            observations.add(out);
        }
        var chosen=best<0?null:answers[best];double ticks=chosen==null?Double.POSITIVE_INFINITY:breakTicks(chosen.strength());
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("bestSlot",best<0?null:best);out.put("forcedTool",ReferenceToolPolicy.forcedTool);
        out.put("harvestable",chosen!=null&&chosen.harvest());out.put("estimatedTicks",Double.isFinite(ticks)?ticks:null);
        return out;
    }
    static Map<String,Object> inspect(World world,BlockPos p) {
        if(!ForgeSnapshot.loaded(world,p.getX(),p.getY(),p.getZ())) throw new IllegalArgumentException("target not loaded");
        List<Map<String,Object>> tools=new ArrayList<>();var best=choose(world,p,tools);
        Map<String,Object> out=new LinkedHashMap<>();out.put("target",List.of(p.getX(),p.getY(),p.getZ()));out.put("tools",tools);out.putAll(best);
        out.put("toolsToAvoid",List.copyOf(Baritone.settings().toolsToAvoid.value));out.put("measuredIneffective",List.copyOf(ineffective));
        out.put("estimateOnly",true);return out;
    }
}
