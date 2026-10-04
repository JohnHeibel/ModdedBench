// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh;

import baritone.Baritone;
import baritone.compat.BlockPos;
import java.util.*;
import java.util.concurrent.atomic.AtomicReferenceArray;
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
    /** The ticks one swing may be held before its tool is measured useless: three times the game's estimate at this
     *  strength and a second. No limit when the game promises no break at all. */
    static int swingLimit(double strength) {
        double expected=breakTicks(strength);
        return Double.isInfinite(expected)?Integer.MAX_VALUE:(int)Math.min(72000,Math.max(60,3*expected+20));
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
    /** Why a stack must not be swung at all: the model avoids it, it measured breaking nothing, or it is one use from breaking
     *  as the game reports damage; swords and itemSaver follow their settings. Nothing here guesses what a tool does by its class.
     *  Whether it can harvest a block and how fast is the game's own answer (below), and what one swing actually broke is
     *  measured by the job that swings it (a 3x3 hammer, a vein miner). */
    static String rejected(ItemStack stack) {
        if(stack==null) return null;
        if(avoided(stack)) return "avoided_by_toolsToAvoid";
        if(ineffective.contains(toolKind(stack))) return "measured_breaking_nothing";
        if(stack.stackSize<=0) return "empty_stack";
        if(stack.isItemStackDamageable() && stack.getMaxDamage()-stack.getItemDamage()<=1) return "durability_reserve";
        var settings=Baritone.settings();
        if(settings.itemSaver.value && stack.isItemStackDamageable() && stack.getItemDamage()+settings.itemSaverThreshold.value>=stack.getMaxDamage()) return "itemSaver_threshold";
        if(!settings.useSwordToMine.value && stack.getItem() instanceof ItemSword) return "sword_kept_by_useSwordToMine";
        return null;
    }
    // The game's own answer to "how much of this block does one tick with this stack in hand break, and does it drop", as
    // vanilla computes it: the stack's dig speed and efficiency, then Forge's BreakSpeed event and harvest check, where a pack
    // rewrites both (a vanilla stone pickaxe here is vetoed by the event and breaks nothing). The player's momentary state (in
    // water, in the air, potions) is left out, so a plan does not change with a jump. Asked on the game thread only. The path
    // search runs on its own thread and never waits for it (a wait per ore cell made underground searches a thousand times
    // slower): on a miss it takes a provisional answer and queues the precise one for the next game tick, and the game thread
    // warms the answers around the player before searches. The executor re-costs each movement on the game thread with
    // precise answers, so a path planned on a provisional answer the game refuses is cancelled and planned again.
    private record Key(net.minecraft.item.Item item,int damage,int nbt,Block block,int meta,long pos) {}
    /** A tool kind (toolKind: the item, and its damage only when it has subtypes) against a block state, at any position. */
    private record Kind(net.minecraft.item.Item item,int damage,Block block,int meta) {}
    private record Ask(ItemStack stack,int x,int y,int z,boolean placed) {}
    private record Known<K>(K key,ReferenceToolPolicy.Answer answer) {}
    /** Who asks the game: ask() below, or a test standing in for it. `queued`: asked a tick or more after the search saw the block. */
    interface Asker {ReferenceToolPolicy.Answer ask(ItemStack stack,Block block,int meta,int x,int y,int z,boolean placed,boolean queued);}
    static volatile Asker game=MiningTools::ask;
    // Direct-mapped tables: a new answer takes its slot, so reading and writing are O(1) without locks, a big search never
    // wipes everything at once, and they never grow. Written on the game thread only.
    static final int SLOTS=1<<16,KINDS=1<<12,ASKED=4096;
    private static final AtomicReferenceArray<Known<Key>> answers=new AtomicReferenceArray<>(SLOTS);
    private static final AtomicReferenceArray<Known<Kind>> weakest=new AtomicReferenceArray<>(KINDS);
    private static final Map<Key,Ask> asked=new java.util.concurrent.ConcurrentHashMap<>();
    /** The game-thread time the queued asks and the warm-up may take per tick, together. */
    static final long TICK_BUDGET_NS=250_000;
    private static volatile Thread gameThread;
    /** A block with a tile entity may keep its identity there (a GregTech ore's material): its answer is per position. */
    static long cell(Block block,int meta,int x,int y,int z,boolean placed) {
        return placed&&block.hasTileEntity(meta)?(((long)x&0x3FFFFFF)<<38)|(((long)y&0xFFF)<<26)|((long)z&0x3FFFFFF):Long.MIN_VALUE;
    }
    private static int nbt(ItemStack stack){return stack!=null&&stack.hasTagCompound()?stack.getTagCompound().hashCode():0;}
    private static Key key(ItemStack stack,Block block,int meta,long cell) {
        return stack==null?new Key(null,0,0,block,meta,cell):new Key(stack.getItem(),stack.getItemDamage(),nbt(stack),block,meta,cell);
    }
    private static Kind kind(ItemStack stack,Block block,int meta) {
        return stack==null?new Kind(null,0,block,meta):new Kind(stack.getItem(),stack.getHasSubtypes()?stack.getItemDamage():0,block,meta);
    }
    private static int slot(Object key,int bits){return (int)((key.hashCode()*0x9E3779B97F4A7C15L)>>>(64-bits));}
    private static ReferenceToolPolicy.Answer known(Key k){var e=answers.get(slot(k,16));return e!=null&&e.key().equals(k)?e.answer():null;}
    private static ReferenceToolPolicy.Answer weakest(Kind k){var e=weakest.get(slot(k,12));return e!=null&&e.key().equals(k)?e.answer():null;}
    /** Game thread: the precise answer, and its kind's weakest so far (the slowest and least harvesting at any position,
     *  durability or NBT), which the search falls back on before the precise one. */
    private static void record(Key k,Kind kind,ReferenceToolPolicy.Answer a) {
        answers.set(slot(k,16),new Known<>(k,a));
        int s=slot(kind,12);var e=weakest.get(s);
        if(e!=null&&e.key().equals(kind)){
            var w=e.answer();if(w.strength()<=a.strength()&&(!w.harvest()||a.harvest()))return;
            a=new ReferenceToolPolicy.Answer(Math.min(w.strength(),a.strength()),w.harvest()&&a.harvest());
        }
        weakest.set(s,new Known<>(kind,a));
    }
    /** A bare hand's rate without harvest at the block's own hardness, asked with no world (no position, nothing a tile
     *  entity holds): vanilla's slowest rate for a block that breaks at all. Null when even that needs the world. */
    static ReferenceToolPolicy.Answer handRate(Block block) {
        float h;try{h=block.getBlockHardness(null,0,0,0);}catch(RuntimeException|LinkageError positionDependent){return null;}
        return Float.isNaN(h)?null:h<0?new ReferenceToolPolicy.Answer(-1,false):new ReferenceToolPolicy.Answer(h==0?Double.POSITIVE_INFINITY:1/(h*100.0),false);
    }
    /** The game's answer for each stack: precise on the game thread, or once the game has answered. Before that, on the
     *  search's thread, a provisional one, never cached: the weakest answer for the stack's kind on this block state, else
     *  handRate, so a plan digs through what it has not measured only where walking round costs more; else null, which
     *  the search takes as unbreakable for now. */
    static ReferenceToolPolicy.Answer[] answers(ItemStack[] stacks,Block block,int meta,int x,int y,int z,boolean placed) {
        var out=new ReferenceToolPolicy.Answer[stacks.length];
        if(gameThread==null) return out;
        long cell=cell(block,meta,x,y,z,placed);boolean here=Thread.currentThread()==gameThread,hand=false;ReferenceToolPolicy.Answer guess=null;
        for(int i=0;i<stacks.length;i++) {
            ItemStack s=stacks[i];Key k=key(s,block,meta,cell);
            if((out[i]=known(k))!=null)continue;
            if(here){if((out[i]=game.ask(s,block,meta,x,y,z,placed,false))!=null)record(k,kind(s,block,meta),out[i]);continue;}
            if(asked.size()<ASKED&&!asked.containsKey(k))asked.putIfAbsent(k,new Ask(s==null?null:s.copy(),x,y,z,placed));
            if((out[i]=weakest(kind(s,block,meta)))==null){if(!hand){guess=handRate(block);hand=true;}out[i]=guess;}
        }
        return out;
    }
    static boolean onGameThread(){return Thread.currentThread()==gameThread;}
    /** Once a game tick: answer what the path search asked since the last one (pick-blocks first), then go on warming,
     *  within TICK_BUDGET_NS. */
    static void answer() {
        gameThread=Thread.currentThread();
        long end=System.nanoTime()+TICK_BUDGET_NS;
        BlockIdentity.answer(end);
        for(var it=asked.entrySet().iterator();it.hasNext()&&System.nanoTime()<end;) {
            var e=it.next();it.remove();var k=e.getKey();var a=e.getValue();
            if(known(k)!=null)continue;
            var answer=game.ask(a.stack(),k.block(),k.meta(),a.x(),a.y(),a.z(),a.placed(),true);
            if(answer!=null)record(k,kind(a.stack(),k.block(),k.meta()),answer);
        }
        warmStep(end);
    }
    // The warm-up: rings round the player out to WARM_R, WARM_Y up and down, every stack the search will ask about each
    // block it might break (and its pick-block, when a hazard rule names one), as much a tick as the budget leaves, resumed where the last tick stopped (within a cell too). A
    // state without a tile entity is asked once a warm-up, a cell with one per position. Game thread only.
    private static final int WARM_R=8,WARM_Y=4;
    private static ItemStack[] warmStacks=new ItemStack[0];
    private static Key[] warmKeys=new Key[0];
    private static int warmStamp,warmX,warmY=-1,warmZ,warmRing,warmK,warmDy,warmI;
    private static boolean warmDone=true;
    private static long warmedAt;
    private static final BitSet warmSeen=new BitSet();
    private static int stamp(ItemStack[] stacks){int h=stacks.length;for(var s:stacks)h=h*31+(s==null?0:System.identityHashCode(s.getItem())*961+s.getItemDamage()*31+nbt(s));return h;}
    /** On the game thread before a search, with the stacks its ToolSet holds: warm the answers around the player over the
     *  next ticks. A warm-up stands while the stacks are the same and the player stays within four blocks of its centre,
     *  and for five seconds once done. Costs nothing itself but the stacks' stamp. */
    static void warm(ItemStack[] stacks,int px,int py,int pz) {
        if(Thread.currentThread()!=gameThread)return;
        int stamp=stamp(stacks);
        if(warmY>=0&&stamp==warmStamp&&Math.abs(px-warmX)<4&&Math.abs(py-warmY)<4&&Math.abs(pz-warmZ)<4&&(!warmDone||System.nanoTime()-warmedAt<5_000_000_000L))return;
        List<ItemStack> distinct=new ArrayList<>();List<Key> keys=new ArrayList<>();
        for(var s:stacks){Key k=key(s,null,0,0);if(!keys.contains(k)){keys.add(k);distinct.add(s);}}
        warmStacks=distinct.toArray(new ItemStack[0]);warmKeys=keys.toArray(new Key[0]);warmStamp=stamp;
        warmX=px;warmY=py;warmZ=pz;warmRing=0;warmK=0;warmDy=-WARM_Y;warmI=0;warmDone=false;warmSeen.clear();
    }
    private static void warmStep(long end) {
        if(warmDone)return;
        Minecraft mc=Minecraft.getMinecraft();World world=mc==null?null:mc.theWorld;
        if(world==null||mc.thePlayer==null)return;
        boolean picks=BlockRules.picksIdentity();
        while(System.nanoTime()<end) {
            int x=warmX+BlockShapes.ringX(warmRing,warmK),z=warmZ+BlockShapes.ringZ(warmRing,warmK),y=warmY+warmDy;
            if(y>=0&&y<=255&&ForgeSnapshot.loaded(world,x,y,z)) {
                Block b=world.getBlock(x,y,z);int meta=world.getBlockMetadata(x,y,z),id=Block.getIdFromBlock(b);
                long cell=cell(b,meta,x,y,z,true);int state=id<0?-1:id<<4|meta&15;
                if(!b.isAir(world,x,y,z)&&(warmI>0||cell!=Long.MIN_VALUE||state<0||!warmSeen.get(state))) {
                    if(cell==Long.MIN_VALUE&&state>=0)warmSeen.set(state);
                    if(warmI==0&&picks)BlockIdentity.warm(b,meta,x,y,z);
                    for(;warmI<warmStacks.length;warmI++) {
                        if(System.nanoTime()>=end)return;
                        Key w=warmKeys[warmI],k=new Key(w.item(),w.damage(),w.nbt(),b,meta,cell);
                        if(known(k)!=null)continue;
                        var a=game.ask(warmStacks[warmI],b,meta,x,y,z,true,false);
                        if(a!=null)record(k,kind(warmStacks[warmI],b,meta),a);
                    }
                }
            }
            warmI=0;
            if(++warmDy<=WARM_Y)continue;
            warmDy=-WARM_Y;
            if(++warmK<(warmRing==0?1:8*warmRing))continue;
            warmK=0;
            if(++warmRing>WARM_R){warmDone=true;warmedAt=System.nanoTime();return;}
        }
    }
    /** Tests: forget every answer, ask and warm-up. */
    static void reset() {
        for(int i=0;i<SLOTS;i++)answers.set(i,null);
        for(int i=0;i<KINDS;i++)weakest.set(i,null);
        asked.clear();warmDone=true;warmY=-1;gameThread=null;BlockIdentity.reset();
    }
    static int pending(){return asked.size();}
    private static ReferenceToolPolicy.Answer ask(ItemStack stack,Block block,int meta,int x,int y,int z,boolean placed,boolean queued) {
        Minecraft mc=Minecraft.getMinecraft();var player=mc.thePlayer;
        if(player==null||mc.theWorld==null) return null;
        // Asked a tick late: the block, or its chunk, may be gone; leave it unanswered.
        if(queued&&placed&&(!ForgeSnapshot.loaded(mc.theWorld,x,y,z)||mc.theWorld.getBlock(x,y,z)!=block||mc.theWorld.getBlockMetadata(x,y,z)!=meta)) return null;
        var none=new ReferenceToolPolicy.Answer(0,false);
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
        gameThread=Thread.currentThread();
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
