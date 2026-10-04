// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh;

import baritone.Baritone;
import baritone.compat.BlockPos;
import baritone.gtnh.pathing.*;
import static baritone.gtnh.pathing.WorkSpec.*;
import java.util.*;

/** Journal, native selectors and action ownership around the actual upstream MineProcess. */
final class MiningProcess extends BulkJob implements PlansWhilePaused {
    private final List<Map<String,Object>> items;
    // With no items named, any gain counts: the inventory at this session's start, per identity (MiningProcess.identity).
    private final Map<String,Integer> start;
    private final int quantity,baseline,gainedBefore;
    private final Bounds bounds;
    private final Baritone engine;
    private final MiningObservation observation;
    private final Map<baritone.api.Settings.Setting<?>,Object> scopedSettings=new LinkedHashMap<>();
    private boolean started;
    // The first scan of the bounds reads the loaded world and stands still: a paused world can do it. The search that
    // follows cannot move there, as the mine process chooses its targets in a tick that may also break and swing.
    private boolean scannedWhilePaused;
    private Integer finalCount;
    private String finalGoal;
    private int inactiveTicks,pathlessTicks,rejectedSeen,rejections,haveAtReject=-1;
    private record DropLocation(int entityId,baritone.compat.BlockPos position){}
    private final Set<DropLocation> retriedDrops=new HashSet<>();
    private List<Map<String,Object>> diagnostics=List.of();
    private List<List<Integer>> lastKnown=List.of(),lastRejected=List.of();
    // Targets the engine dropped, and why. Unreachable ones are journaled: a resume does not walk back to them unless it
    // passes retry:true; every other reason is the world's and the inventory's, and is judged again as they change.
    private final Set<BlockPos> unreachable=new LinkedHashSet<>();
    private Map<BlockPos,String> skippedNow=Map.of();
    private List<Map<String,Object>> skipped=List.of();
    private final boolean besideFluid;
    private final String toolSlotTool;
    private BlockPos breaking;
    private final Set<BlockPos> plugged=new HashSet<>();
    // Plugs placed and the tick until which each is re-measured: a placement the server refused, or fluid already in, shows there.
    private final Map<BlockPos,Integer> confirming=new HashMap<>();
    private int unplugged;
    // What each swing broke besides the block it was aimed at. The job knows no tool by name, so a 3x3 hammer or a vein
    // miner shows up here as a measurement, and a swing that took a protected block ends the job. A swing is one unbroken
    // hold of the attack on one block with one tool; start times it against the game's own break estimate, asked every
    // tick and kept at its longest (afloat or in the air the game breaks slower). `open` is false in a region the harness
    // refuses to edit.
    private record Swing(BlockPos target,net.minecraft.block.Block block,Map<BlockPos,net.minecraft.block.Block> around,int due,int start,String tool,boolean open){}
    private final List<Map<String,Object>> ineffective=new ArrayList<>();
    private Swing swing;
    private final List<Swing> settling=new ArrayList<>();
    private final Set<BlockPos> aimed=new HashSet<>();
    private int broken,extraBroken,reachableAttackTicks,swingLimit,swingBudget;
    private final List<List<Integer>> extraAt=new ArrayList<>();
    private Integer dropsLeft;
    // The blocks the path placed to climb or bridge (never the fluid plugs, which hold fluid back), with the block each became.
    // cleanupScaffold breaks them again when the work ends as work does; an emergency or a cancel leaves them standing.
    private final boolean cleanupScaffold;
    private final Map<BlockPos,net.minecraft.block.Block> scaffold=new LinkedHashMap<>();
    private final Map<BlockPos,Integer> placing=new HashMap<>(); // a click the game took, and the tick its block is looked at
    private MiningObservation cleanup;
    private Integer countAtCleanup;
    private String cleanupEnd;
    private int scaffoldRemoved,clearedAt=-1;
    private final List<Map<String,Object>> scaffoldLeft=new ArrayList<>();
    MiningProcess(BaritoneNavigation nav,WorkJournal journal,Map<String,Object> options){
        super(nav,journal,options);engine=nav.reference();
        var blocks=WorkAccess.selectors(params.get("blocks"));items=params.containsKey("items")?WorkAccess.itemSelectors(params.get("items")):List.of();
        start=items.isEmpty()?stacks():null;
        quantity=integer(params,"quantity",1,1,1000000);
        besideFluid=bool(params,"besideFluid",false);
        // toolSlot forces the tool in that slot now, wherever a swap later moves it; what it breaks is still measured.
        if(params.containsKey("toolSlot"))toolSlotTool=ReferenceToolPolicy.remember(journal.progress,()->{
            var stack=mc.thePlayer.inventory.getStackInSlot(integer(params,"toolSlot",0,0,35));
            if(stack==null)throw new IllegalArgumentException("toolSlot is empty");return MiningTools.toolKind(stack);});
        else toolSlotTool=null;
        if(besideFluid&&!allowPlace)throw new IllegalArgumentException("besideFluid plugs the holes it opens: it needs allowPlace and a throwaway block (cobblestone, dirt) in the hotbar");
        BlockPos origin=WorkAccess.feet();int radius=integer(params,"radius",24,1,64);
        Map<String,Object> scan=params.containsKey("bounds")?child(params,"bounds"):Map.of("min",List.of(origin.getX()-radius,Math.max(1,origin.getY()-16),origin.getZ()-radius),"max",List.of(origin.getX()+radius,Math.min(254,origin.getY()+16),origin.getZ()+radius));
        bounds=bounds(scan);if(bounds.volume()>262144)throw new IllegalArgumentException("mining scan exceeds 262144 cells");
        journal.spec.put("bounds",scan);
        // Gain is summed over sessions, each measured from its own start, so ore smelted or stored between a pause and
        // the resume still counts and ore fetched from a chest meanwhile does not.
        int now=have(),legacy=journal.progress.containsKey("initialCount")?Math.max(0,now-integer(journal.progress,"initialCount",now,0,1000000)):0;
        gainedBefore=integer(journal.progress,"gained",legacy,0,1000000);baseline=now-gainedBefore;
        observation=new MiningObservation(world,bounds,blocks,items,WorkAccess::feet);
        if(!bool(options,"retry",false))for(Object p:list(journal.progress.getOrDefault("unreachable",List.of())))unreachable.add(pos(p));
        rejectedSeen=unreachable.size();
        cleanupScaffold=bool(params,"cleanupScaffold",false);
        for(Object row:list(journal.progress.getOrDefault("scaffold",List.of()))){
            var cell=list(row);var block=net.minecraft.block.Block.getBlockFromName(String.valueOf(cell.get(3)));
            if(block!=null)scaffold.put(pos(cell.subList(0,3)),block);
        }
    }
    @Override void begin(){
        super.begin();engine.getPathingBehavior().forceCancel();
        for(var setting:List.of(Baritone.settings().allowBreak,Baritone.settings().allowPlace,Baritone.settings().exploreForBlocks,Baritone.settings().legitMine,Baritone.settings().allowInventory))scopedSettings.put(setting,setting.value);
        Baritone.settings().allowInventory.value=true; // the best tool anywhere in the inventory, not only the hotbar
        MiningTools.ineffective.clear();BlockRules.reset();engine.snags.reset();baritone.gtnh.pathing.Cost.reset();ReferenceToolPolicy.forcedTool=toolSlotTool;
        Baritone.settings().allowBreak.value=allowBreak;Baritone.settings().allowPlace.value=allowPlace;
        // This action has explicit observation bounds. Exploration is a separate
        // process, not permission to start a branch mine when its bounds empty.
        Baritone.settings().exploreForBlocks.value=false;Baritone.settings().legitMine.value=false;
        engine.overrideProtection=override;engine.positionAllowed=p->true;
        engine.explicitMiningTargets=observation::capture;
        Baritone.besideFluid=besideFluid;
        // A plug is never walked back through: the search may not stand in one, nor under one.
        if(besideFluid)engine.positionAllowed=p->!plugged.contains(p)&&!plugged.contains(new BlockPos(p.getX(),p.getY()+1,p.getZ()));
        engine.getPlayerContext().playerController().placed=this::placing;
        engine.getInputOverrideHandler().attach(lease);
    }
    int gained(){return Math.max(0,have()-baseline);}
    /** The named items held, or with none named the sum of each identity's gain since this session started. */
    private int have(){
        if(start==null)return WorkAccess.count(items);
        int gain=0;for(var e:stacks().entrySet())gain+=Math.max(0,e.getValue()-start.getOrDefault(e.getKey(),0));return gain;
    }
    /** An item's identity is its id, meta and NBT; a stack of one (a tool, whose wear some mods keep in NBT) is its id and meta,
     *  and a damageable item is its id alone: vanilla keeps wear in the meta, and a pickaxe worn by a swing is not a gain. */
    static String identity(net.minecraft.item.ItemStack s){
        return baritone.compat.Registry.name(s.getItem())+(s.isItemStackDamageable()?"":":"+s.getItemDamage())+(s.getMaxStackSize()>1&&s.hasTagCompound()?s.getTagCompound().toString():"");
    }
    private static Map<String,Integer> stacks(){
        Map<String,Integer> out=new HashMap<>();
        for(var s:WorkAccess.MC.thePlayer.inventory.mainInventory)if(s!=null&&s.stackSize>0)out.merge(identity(s),s.stackSize,Integer::sum);
        return out;
    }
    @Override int progress(){return mc.thePlayer==player?gained():progressSeen;}
    // Digging toward a target is work before any ore arrives, and so is the first scan of the bounds, which stands still.
    // Taking the scaffold down is new work too: its start counts, so the watchdog that ended the mining does not end it at once.
    @Override long activity(){return progress()+(long)broken+(observation.passes==0?observation.cursor:0)+(closing()?1_000_000L:0);}
    // Holding a swing the game promised to finish is waiting for as long as that promise runs: a slow block is no stall.
    // A block the game will never break, a protected one, or a swing past its limit is excused nothing.
    @Override int excused(){return Math.max(super.excused(),swingBudget);}
    @Override String phase(){return closing()?"scaffold_cleanup":"reference_mine";}
    @Override public boolean planningWhilePaused(){return !done()&&ticks==0&&!started&&observation.passes==0;}
    @Override public void planWhilePaused(){
        if(mc.theWorld==world&&mc.thePlayer==player&&lease!=null&&lease.isActive()){observation.tick();scannedWhilePaused=observation.passes>0;}
    }
    @Override void step(){
        placed();
        if(closing()){cleanStep();return;}
        if(gained()>=quantity){finish("succeeded","requested_inventory_gain_observed");return;}
        if(!WorkAccess.room(items)){finish("failed","inventory_full");return;}
        observation.tick();
        if(!started){
            state="scanning";if(observation.passes==0)return;
            // Give the original inventory scheduler its ticks before the initial
            // MineProcess rescan prunes ores using the current hotbar's harvest capability.
            engine.tickStart();
            if(engine.getInventoryBehavior().hasPendingMove()){state="preparing_inventory";return;}
            // The public action counts net gains across resume; the upstream
            // process continues until this wrapper's inventory condition fires.
            engine.bsi=new baritone.utils.BlockStateInterface(engine.getPlayerContext());
            var costs=new baritone.pathing.movement.CalculationContext(engine);
            diagnostics=observation.observedLocations().stream().limit(16).map(p->{
                var s=costs.get(p);double duration=baritone.pathing.movement.MovementHelper.getMiningDurationTicks(costs,p.getX(),p.getY(),p.getZ(),true);
                return Map.<String,Object>of("pos",point(p),"matches",observation.has(s),"canHarvest",costs.toolSet.canHarvest(s),"bestSlot",costs.toolSet.getBestSlot(s),"miningCost",duration,
                    "breakSafetyBlocked",baritone.pathing.movement.MovementHelper.avoidBreaking(costs.bsi,p.getX(),p.getY(),p.getZ(),s),
                    "above",costs.get(p.getX(),p.getY()+1,p.getZ()).toString());
            }).toList();
            engine.getMineProcess().mine(0,observation);engine.getMineProcess().avoid(unreachable);started=true;
            return;
        }
        var process=engine.getMineProcess();
        if(!process.isActive()){
            // A bounded, net-gain action must settle already-issued edits before
            // interpreting the upstream process stopping as a failed receipt.
            // Newly arriving drops still use MineProcess's own collection goals.
            if(inactiveTicks++==0){engine.getPathingBehavior().forceCancel();engine.getInputOverrideHandler().clearAllKeys();engine.getInputOverrideHandler().flush();}
            boolean newDrop=false;
            for(Object entity:world.loadedEntityList)if(entity instanceof net.minecraft.entity.item.EntityItem drop&&!drop.isDead
                    &&observation.has(drop.getEntityItem())
                    &&observation.acceptsDrop(new baritone.compat.BlockPos(drop.posX,drop.boundingBox.minY,drop.posZ))
                    &&retriedDrops.add(new DropLocation(drop.getEntityId(),new baritone.compat.BlockPos(drop.posX,drop.boundingBox.minY,drop.posZ))))newDrop=true;
            // The engine was offered only the nearest matches: before its stopping is read as nothing left, it is offered more.
            if(newDrop||observation.widen()){engine.bsi=new baritone.utils.BlockStateInterface(engine.getPlayerContext());process.mine(0,observation);process.avoid(unreachable);}
            if(!process.isActive()){
                state="awaiting_inventory";
                if(inactiveTicks>Math.max(20,(Baritone.settings().mineDropLoiterDurationMSThanksLouca.value+49)/50))finish("failed",naming("no_remaining_reachable_targets_or_drops"));
                return;
            }
        }
        inactiveTicks=0;
        if(besideFluid&&(unconfirmedPlug()||plug()))return; // this tick belongs to the plug
        engine.tickStart(this::mineAtReachedGoal);
        // Judged as the deadline is: a session that gained something pauses, and a resume starts with no bans.
        if(engine.snags.failure()!=null){finish(session()>0?"paused":"failed",engine.snags.failure());return;}
        if(besideFluid)watch();
        if(measure())return;
        if(process.isActive()){
            lastKnown=process.knownLocations().stream().map(MiningProcess::point).toList();
            lastRejected=process.rejectedLocations().stream().map(MiningProcess::point).toList();
            unreachable.addAll(process.rejectedLocations());skippedNow=process.skippedLocations();
        }
        // A failed search blacklists ONE target and plans again, seconds apiece. Four in a row with nothing gained between
        // them is a deposit this player cannot reach: end with the reason instead of working through every block of it.
        if(lastRejected.size()>rejectedSeen){
            rejectedSeen=lastRejected.size();int have=have();
            rejections=have==haveAtReject?rejections+1:1;haveAtReject=have;
            if(rejections>=4){finish("failed",naming("no_path_to_targets"));return;}
            // Unreachable targets among reachable ones (the tops of trees) reset that count with every block gained, and the
            // job spends most of its time on searches that fail. More failed searches than blocks gained ends it the same way.
            if(rejectedSeen>Math.max(8,gained())){finish("failed",naming("no_path_to_most_targets"));return;}
        }
        state=engine.getInputOverrideHandler().isInputForcedDown(baritone.api.utils.input.Input.CLICK_LEFT)?"mining":"pathing";
        // MineProcess keeps its goal while every remaining target is one it may not break (beside still water, say) or
        // cannot reach, and the player would stand there until the timeout. Five seconds with no path and none being
        // planned is that case: end with the reason instead.
        boolean pathless=state.equals("pathing")&&engine.getPathingBehavior().getCurrent()==null&&!engine.getPathingBehavior().getInProgress().isPresent();
        pathlessTicks=pathless?pathlessTicks+1:0;
        if(pathlessTicks>100)finish("failed",naming("no_path_to_remaining_targets"));
    }
    /** An end for want of targets names the nearest one still standing and why it was left: the cell and cause to act on. */
    static String naming(String why,BlockPos feet,Collection<BlockPos> standing,java.util.function.Function<BlockPos,String> left){
        var nearest=standing.stream().min(Comparator.comparingDouble(feet::distanceSq)).orElse(null);
        if(nearest==null)return why;
        String cause=left.apply(nearest);
        return why+": nearest target "+nearest.getX()+","+nearest.getY()+","+nearest.getZ()+(cause==null?"":" "+cause);
    }
    private String naming(String why){
        if(mc.thePlayer!=player)return why;
        var bsi=new baritone.utils.BlockStateInterface(engine.getPlayerContext());
        return naming(why,WorkAccess.feet(),observation.observedLocations().stream().filter(p->observation.has(bsi.get0(p))).toList(),p->{
            String known=unreachable.contains(p)?"unreachable":skippedNow.get(p);
            if(known!=null)return known;
            // The engine forgets what it skipped when it stops itself: ask what it asked, and keep the answer for the receipt.
            var costs=new baritone.pathing.movement.CalculationContext(engine);
            known=!costs.toolSet.canHarvest(costs.get(p))?"no_tool_in_inventory_harvests_it":!baritone.process.MineProcess.plausibleToBreak(costs,p)?"will_not_break_here":null;
            if(known!=null){skippedNow=new LinkedHashMap<>(skippedNow);skippedNow.put(p,known);}
            return known;
        });
    }
    /** An exposed target need not obstruct a movement or stand directly over the player. */
    private void mineAtReachedGoal(){
        var pathing=engine.getPathingBehavior();var goal=pathing.getGoal();var feet=WorkAccess.feet();
        if(goal==null||!goal.isInGoal(feet)||!mc.thePlayer.onGround||!pathing.isSafeToCancel()
                ||engine.getInputOverrideHandler().isInputForcedDown(baritone.api.utils.input.Input.CLICK_LEFT))return;
        var eye=mc.thePlayer.getPosition(1);double reach=mc.playerController.getBlockReachDistance();
        var targets=closing()?cleanup:observation;
        for(var p:engine.getMineProcess().knownLocations().stream().sorted(Comparator.comparingDouble(feet::distanceSq)).toList()){
            if(p.getY()<feet.getY())continue; // Never turn an idle stance into a downward dig.
            if(!MiningJob.near(eye,p,reach))continue; // nothing is read or traced for a target no ray could reach
            var block=engine.bsi.get0(p);
            if(!targets.has(block)||baritone.pathing.movement.MovementHelper.avoidBreaking(engine.bsi,p.getX(),p.getY(),p.getZ(),block))continue;
            var point=MiningJob.reachable(mc,world,p,eye);if(point==null)continue;
            var input=engine.getInputOverrideHandler();input.clearAllKeys();
            baritone.pathing.movement.MovementHelper.switchToBestToolFor(engine.getPlayerContext(),block);
            if(engine.getInventoryBehavior().hasPendingMove())return;
            double dx=point.xCoord-eye.xCoord,dy=point.yCoord-eye.yCoord,dz=point.zCoord-eye.zCoord;
            lease.look((float)Math.toDegrees(Math.atan2(-dx,dz)),(float)-Math.toDegrees(Math.atan2(dy,Math.hypot(dx,dz))));
            dev.modbench.api.ControlRegistry.targeting().refresh();var hit=mc.objectMouseOver;
            if(hit!=null&&hit.typeOfHit==net.minecraft.util.MovingObjectPosition.MovingObjectType.BLOCK
                    &&hit.blockX==p.getX()&&hit.blockY==p.getY()&&hit.blockZ==p.getZ()){
                input.setInputForceState(baritone.api.utils.input.Input.CLICK_LEFT,true);reachableAttackTicks++;
            }
            return;
        }
    }
    /** The path's click placed into this cell: a few ticks on, once the server has had its say, the block there is scaffold. */
    private void placing(BlockPos p){if(!closing()&&!scaffold.containsKey(p))placing.putIfAbsent(p,ticks+5);}
    private void placed(){
        for(var it=placing.entrySet().iterator();it.hasNext();){
            var e=it.next();if(ticks<e.getValue())continue;
            var p=e.getKey();var b=world.getBlock(p.getX(),p.getY(),p.getZ());
            if(b.getMaterial().blocksMovement())scaffold.put(p,b);
            it.remove();
        }
    }
    /** The work is over: with cleanupScaffold, first break the blocks it placed, unless this is an ending to get away from. */
    @Override boolean closing(String terminal,String why){
        if(!cleanupScaffold||!routine(terminal,why)||scaffold.isEmpty()||WorkAccess.died(player)||mc.theWorld!=world||mc.thePlayer!=player)return false;
        return startCleanup();
    }
    /** Work ending as work does: done, full, out of reachable targets, out of time or stalled. Death, a fluid it could not
     *  plug, a protected block broken, an error or a cancel (the model's, or another job's start) is no time to tidy up. */
    private static boolean routine(String terminal,String why){
        return terminal.equals("succeeded")||!terminal.equals("cancelled")&&(why.equals("inventory_full")||why.startsWith("no_remaining")
            ||why.startsWith("no_path")||why.startsWith("timeout_")||why.startsWith("stalled_"));
    }
    /** True when there is scaffold to break: the job then runs on, closing, on a budget of its own. */
    private boolean startCleanup(){
        countAtCleanup=have();
        engine.getPathingBehavior().forceCancel();engine.getInputOverrideHandler().clearAllKeys();
        Baritone.settings().allowPlace.value=false;Baritone.besideFluid=false; // no new scaffold to take the old one down
        Map<BlockPos,baritone.compat.IBlockState.StateKey> cells=new LinkedHashMap<>();
        scaffold.forEach((p,block)->{
            var now=world.getBlock(p.getX(),p.getY(),p.getZ());
            if(now.getMaterial()==net.minecraft.block.material.Material.air)scaffoldRemoved++; // dug out on the way already
            else if(now!=block)leave(p,"changed");
            else if(plugged.contains(p)||wet(p)||harmful(p))leave(p,"beside_fluid"); // it holds fluid back now
            else cells.put(p,new baritone.compat.IBlockState.StateKey(now,world.getBlockMetadata(p.getX(),p.getY(),p.getZ())));
        });
        if(cells.isEmpty()){cleanupEnd="done";return false;}
        cleanup=MiningObservation.cells(world,cells);engine.explicitMiningTargets=cleanup::capture;
        engine.bsi=new baritone.utils.BlockStateInterface(engine.getPlayerContext());engine.getMineProcess().mine(0,cleanup);
        remaining=Math.max(remaining,600+200*cells.size());inactiveTicks=0;pathlessTicks=0;return true;
    }
    private void cleanStep(){
        var process=engine.getMineProcess();
        boolean standing=cleanup.observedLocations().stream().anyMatch(p->world.getBlock(p.getX(),p.getY(),p.getZ())==scaffold.get(p));
        if(!standing&&clearedAt<0)clearedAt=ticks;
        // All down: the drops it loiters for are the scaffold's blocks back, a few seconds' worth.
        if(!standing&&(!process.isActive()||ticks-clearedAt>200)){endCleanup("done");return;}
        if(!process.isActive()){
            if(inactiveTicks++>Math.max(20,(Baritone.settings().mineDropLoiterDurationMSThanksLouca.value+49)/50))endCleanup("no_remaining_reachable_targets");
            return;
        }
        inactiveTicks=0;
        engine.tickStart(this::mineAtReachedGoal);
        if(engine.snags.failure()!=null){endCleanup(engine.snags.failure());return;}
        if(measure())return;
        boolean pathless=!engine.getInputOverrideHandler().isInputForcedDown(baritone.api.utils.input.Input.CLICK_LEFT)
            &&engine.getPathingBehavior().getCurrent()==null&&!engine.getPathingBehavior().getInProgress().isPresent();
        pathlessTicks=pathless?pathlessTicks+1:0;
        if(pathlessTicks>100)endCleanup("no_path_to_remaining_targets");
    }
    private void endCleanup(String why){tally(why);closed();}
    /** What became of each block the cleanup set out to break. */
    private void tally(String why){
        cleanupEnd=why;
        for(var p:cleanup.observedLocations()){
            var now=world.getBlock(p.getX(),p.getY(),p.getZ());
            if(now==scaffold.get(p))leave(p,why);else if(now.getMaterial()==net.minecraft.block.material.Material.air)scaffoldRemoved++;else leave(p,"changed");
        }
    }
    private void leave(BlockPos p,String why){
        if(scaffoldLeft.size()<32)scaffoldLeft.add(Map.of("pos",point(p),"block",String.valueOf(net.minecraft.block.Block.blockRegistry.getNameForObject(world.getBlock(p.getX(),p.getY(),p.getZ()))),"why",why));
    }
    @Override void releaseProcess(){
        engine.getPlayerContext().playerController().placed=cell->{};
        if(cleanup!=null&&cleanupEnd==null&&mc.thePlayer==player)tally(ending.cut().isEmpty()?"interrupted":ending.cut()); // the closing was cut short
        if(mc.thePlayer==player)journal.progress.put("scaffold",scaffold.entrySet().stream().filter(e->world.getBlock(e.getKey().getX(),e.getKey().getY(),e.getKey().getZ())==e.getValue()).limit(256)
            .map(e->List.<Object>of(e.getKey().getX(),e.getKey().getY(),e.getKey().getZ(),String.valueOf(net.minecraft.block.Block.blockRegistry.getNameForObject(e.getValue())))).toList());
        finalCount=mc.thePlayer==player?countAtCleanup!=null?countAtCleanup:have():null; // the scaffold's own blocks back are no gain
        if(finalCount!=null)journal.progress.put("gained",Math.max(0,finalCount-baseline));
        if(mc.thePlayer==player&&observation!=null){int left=0;
            for(Object entity:world.loadedEntityList)if(entity instanceof net.minecraft.entity.item.EntityItem drop&&!drop.isDead&&observation.has(drop.getEntityItem())
                    &&bounds.contains(new BlockPos(drop.posX,drop.boundingBox.minY,drop.posZ)))left+=drop.getEntityItem().stackSize;
            dropsLeft=left;}
        finalGoal=String.valueOf(engine.getPathingBehavior().getGoal());
        engine.getPathingBehavior().forceCancel();engine.getInputOverrideHandler().release();
        engine.explicitMiningTargets=()->s->false;Baritone.besideFluid=false;MiningTools.ineffective.clear();ReferenceToolPolicy.forcedTool=null;
        skipped=skipped();journal.progress.put("unreachable",unreachable.stream().limit(256).map(MiningProcess::point).toList());
        scopedSettings.forEach(ReferenceSettings::copy);
    }
    @Override public Map<String,Object> status(){
        Map<String,Object> out=super.status();
        out.put("engine","baritone-1.2.19-source-port");out.put("process","MineProcess");
        out.put("quantity",quantity);out.put("gainedBefore",gainedBefore);
        Integer count=done()?finalCount:mc.thePlayer==player?have():null;out.put("items",start==null?items:"any");
        out.put("currentCount",count);out.put("gained",count==null?null:Math.max(0,count-baseline));
        out.put("scanPasses",observation==null?0:observation.passes);out.put("scannedWhilePaused",scannedWhilePaused);out.put("scanCursor",observation==null?0:observation.cursor);
        out.put("scanVolume",bounds==null?0:bounds.volume());out.put("scanMatches",observation==null?0:observation.matches());out.put("scanOffered",observation==null?0:observation.observedLocations().size());out.put("targets",lastKnown);out.put("bounds",journal.spec.get("bounds"));
        out.put("initialTargetDiagnostics",diagnostics);
        out.put("reachableAttackTicks",reachableAttackTicks);
        // Targets it left, and why: will_not_break_here names the fluid beside it (plug or drain that, or pass besideFluid).
        var left=done()?skipped:skipped();out.put("skipped",left.stream().limit(16).toList());out.put("skippedCount",left.size());
        out.put("plugged",plugged.stream().map(MiningProcess::point).toList());out.put("plugFailures",unplugged);
        Map<String,Object> sc=new LinkedHashMap<>();
        sc.put("cleanup",!cleanupScaffold?"off":cleanupEnd!=null?cleanupEnd:closing()?"running":!done()?"after_the_work":scaffold.isEmpty()?"nothing_placed":"skipped");
        sc.put("placed",scaffold.size());sc.put("at",scaffold.keySet().stream().limit(32).map(MiningProcess::point).toList());
        sc.put("removed",scaffoldRemoved);sc.put("left",scaffoldLeft);out.put("scaffold",sc);
        out.put("blocksBroken",broken);out.put("extraBroken",extraBroken);out.put("extraBrokenAt",extraAt);out.put("dropsLeftInBounds",dropsLeft);out.put("ineffectiveTools",ineffective);out.put("pathRules",BlockRules.applied());out.put("forcedTool",toolSlotTool);
        if(engine!=null)out.put("snags",engine.snags.status());out.put("cost",baritone.gtnh.pathing.Cost.status());
        if(engine!=null){
            var current=engine.getPathingBehavior().getCurrent();
            out.put("goal",done()?finalGoal:String.valueOf(engine.getPathingBehavior().getGoal()));
            out.put("path",current==null?List.of():current.getPath().positions().stream().map(MiningProcess::point).toList());
            out.put("planning",engine.getPathingBehavior().getInProgress().isPresent());
        }
        out.put("completionMeaning",start==null?"matching inventory gain, summed over this job's sessions (each measured from its own start)":"with no items named: any inventory gain, the sum of each item identity's increase (id+meta+NBT; a stack-of-one item by id+meta) since the session's start, summed over sessions; currentCount is that gain");return out;
    }
    /** Watch the block under the pick; a few ticks after it goes (the server's word on what else broke arrives late),
     *  count every solid neighbour that went with it. A tool the game says breaks the block, held on it three times as long
     *  as the game's own estimate with nothing broken, is measured useless for the rest of the job: every tool choice skips
     *  it and the receipt names it. True when the job has ended here. */
    private boolean measure(){
        var over=mc.objectMouseOver;var held=mc.thePlayer.getHeldItem();var tool=held==null?null:MiningTools.toolKind(held);
        BlockPos aim=engine.getInputOverrideHandler().isInputForcedDown(baritone.api.utils.input.Input.CLICK_LEFT)&&over!=null
            &&over.typeOfHit==net.minecraft.util.MovingObjectPosition.MovingObjectType.BLOCK?new BlockPos(over.blockX,over.blockY,over.blockZ):null;
        swingBudget=0;
        if(swing!=null){
            var t=swing.target();
            if(world.getBlock(t.getX(),t.getY(),t.getZ())!=swing.block()){broken++;settling.add(new Swing(t,swing.block(),swing.around(),ticks+5,swing.start(),swing.tool(),false));swing=null;}
            else if(aim==null||!aim.equals(t)||!Objects.equals(tool,swing.tool()))swing=null; // let go, looked away or changed tool: that swing ended short
            else{
                // Only a break the game promised can fail: an unbreakable block, or one in a region the harness refuses to edit,
                // stays for reasons that say nothing about the tool.
                swingLimit=Math.max(swingLimit,swing.open()?MiningTools.swingLimit(swing.block().getPlayerRelativeBlockHardness(mc.thePlayer,world,t.getX(),t.getY(),t.getZ())):Integer.MAX_VALUE);
                if(ticks-swing.start()>swingLimit){
                    if(tool!=null&&MiningTools.ineffective.add(tool)&&ineffective.size()<8)ineffective.add(Map.of("tool",tool,
                        "block",String.valueOf(net.minecraft.block.Block.blockRegistry.getNameForObject(swing.block())),"at",point(t),"heldTicks",ticks-swing.start()));
                    swing=null;return false;
                }
                if(swingLimit!=Integer.MAX_VALUE)swingBudget=swingLimit;
            }
        }
        for(var it=settling.iterator();it.hasNext();){
            var s=it.next();if(s.due()>ticks)continue;it.remove();
            for(var e:s.around().entrySet()){
                var q=e.getKey();var now=world.getBlock(q.getX(),q.getY(),q.getZ());var was=e.getValue();
                // A torch or snow layer that dropped when its support went, or gravel that fell, was not broken by the tool.
                if(now==was||now.getMaterial()!=net.minecraft.block.material.Material.air||aimed.contains(q)||was instanceof net.minecraft.block.BlockFalling||!was.getMaterial().blocksMovement())continue;
                extraBroken++;if(extraAt.size()<16)extraAt.add(point(q));
                if(WorkAccess.protection(q,override)!=null){finish("failed","tool_broke_protected_block_at_"+q.getX()+","+q.getY()+","+q.getZ());return true;}
            }
        }
        if(swing!=null||aim==null)return false;
        Map<BlockPos,net.minecraft.block.Block> around=new HashMap<>();
        for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++)for(int dz=-1;dz<=1;dz++){
            var b=world.getBlock(aim.getX()+dx,aim.getY()+dy,aim.getZ()+dz);
            if((dx|dy|dz)!=0&&b.getMaterial()!=net.minecraft.block.material.Material.air)around.put(new BlockPos(aim.getX()+dx,aim.getY()+dy,aim.getZ()+dz),b);}
        if(aimed.size()>4096)aimed.clear();aimed.add(aim);
        swing=new Swing(aim,world.getBlock(aim.getX(),aim.getY(),aim.getZ()),around,0,ticks,tool,WorkAccess.protection(aim,override)==null);swingLimit=0;return false;
    }
    private boolean wet(BlockPos p){
        for(int[] d:new int[][]{{0,1,0},{1,0,0},{-1,0,0},{0,0,1},{0,0,-1}})if(world.getBlock(p.getX()+d[0],p.getY()+d[1],p.getZ()+d[2]).getMaterial().isLiquid())return true;
        return false;
    }
    /** A fluid beside p that is not water (lava, or a mod's hot or harmful fluid): the one a failed plug must end the job for. */
    private boolean harmful(BlockPos p){
        for(int[] d:new int[][]{{0,1,0},{1,0,0},{-1,0,0},{0,0,1},{0,0,-1},{0,-1,0}}){var m=world.getBlock(p.getX()+d[0],p.getY()+d[1],p.getZ()+d[2]).getMaterial();if(m.isLiquid()&&m!=net.minecraft.block.material.Material.water)return true;}
        return false;
    }
    /** Remember the block under the pick while it has fluid beside it; once it is gone it stays remembered until plugged. */
    private void watch(){
        if(breaking!=null&&!world.getBlock(breaking.getX(),breaking.getY(),breaking.getZ()).getMaterial().blocksMovement())return;
        var over=mc.objectMouseOver;breaking=null;
        if(!engine.getInputOverrideHandler().isInputForcedDown(baritone.api.utils.input.Input.CLICK_LEFT)||over==null||over.typeOfHit!=net.minecraft.util.MovingObjectPosition.MovingObjectType.BLOCK)return;
        var p=new BlockPos(over.blockX,over.blockY,over.blockZ);if(wet(p)&&(harmful(p)||!passage(p)))breaking=p;
    }
    /** A cell the path digs out to move through: water coming in only wets it, and a plug there blocks the path's own way. */
    private boolean passage(BlockPos p){
        var current=engine.getPathingBehavior().getCurrent();if(current==null)return false;
        var moves=current.getPath().movements();
        for(int i=Math.max(0,current.getPosition());i<moves.size();i++)
            for(var b:((baritone.pathing.movement.Movement)moves.get(i)).toBreakAll())if(b.getX()==p.getX()&&b.getY()==p.getY()&&b.getZ()==p.getZ())return true;
        return false;
    }
    /** The tick after a block beside fluid breaks, before the fluid has moved: put a throwaway block where it was. */
    private boolean plug(){
        if(breaking==null||world.getBlock(breaking.getX(),breaking.getY(),breaking.getZ()).getMaterial().blocksMovement())return false;
        BlockPos p=breaking;breaking=null;
        if(!wet(p))return false;
        if(!engine.getInventoryBehavior().selectThrowawayForLocation(true,p.getX(),p.getY(),p.getZ())){unplugged++;if(!harmful(p))return false;finish("failed","no_throwaway_block_to_plug_fluid");return true;}
        // {neighbour offset, the face of that neighbour which looks at the hole}: floor first, as a player would click
        for(int[] n:new int[][]{{0,-1,0,1},{-1,0,0,5},{1,0,0,4},{0,0,-1,3},{0,0,1,2},{0,1,0,0}}){
            int x=p.getX()+n[0],y=p.getY()+n[1],z=p.getZ()+n[2];var material=world.getBlock(x,y,z).getMaterial();
            if(!material.isSolid()||material.isLiquid())continue;
            var hit=net.minecraft.util.Vec3.createVectorHelper(x+.5-n[0]*.5,y+.5-n[1]*.5,z+.5-n[2]*.5);
            var me=mc.thePlayer;double dx=hit.xCoord-me.posX,dy=hit.yCoord-(me.boundingBox.minY+me.getEyeHeight()),dz=hit.zCoord-me.posZ;
            me.rotationYaw=(float)(Math.toDegrees(Math.atan2(dz,dx))-90);me.rotationPitch=(float)-Math.toDegrees(Math.atan2(dy,Math.sqrt(dx*dx+dz*dz)));
            engine.getInputOverrideHandler().clearAllKeys();engine.getInputOverrideHandler().getBlockBreakHelper().stopBreakingBlock();
            if(mc.playerController.onPlayerRightClick(me,world,me.getHeldItem(),x,y,z,n[3],hit)){me.swingItem();plugged.add(p);confirming.put(p,ticks+5);return true;}
        }
        // Nothing to place against: the fluid is coming in, and mining on beside lava is how the player died. Water only wets.
        unplugged++;if(!harmful(p))return false;finish("failed","unplugged_fluid_at_"+p.getX()+","+p.getY()+","+p.getZ());return true;
    }
    /** From the tick after it was placed, each plug must measure solid for five ticks; true when one failed and the job ended. */
    private boolean unconfirmedPlug(){
        for(var it=confirming.entrySet().iterator();it.hasNext();){
            var e=it.next();var p=e.getKey();
            if(!world.getBlock(p.getX(),p.getY(),p.getZ()).getMaterial().blocksMovement()){
                unplugged++;
                if(harmful(p)){finish("failed","unplugged_fluid_at_"+p.getX()+","+p.getY()+","+p.getZ());return true;}
                it.remove();continue;  // water washed the plug out, or it never held: a wet hole, not a danger
            }
            if(ticks>=e.getValue())it.remove();
        }
        return false;
    }
    /** Every target the engine dropped with its reason, unreachable ones included, and the fluid beside those it will not break. */
    private List<Map<String,Object>> skipped(){
        List<Map<String,Object>> out=new ArrayList<>();
        if(mc.thePlayer!=player)return out;
        Map<BlockPos,String> all=new LinkedHashMap<>(skippedNow);for(var p:unreachable)all.put(p,"unreachable");
        var memory=dev.modbench.api.ControlRegistry.memory().memory().snapshot();
        all.forEach((p,why)->{
            var named=!why.equals("will_not_break_here")?List.<String>of():memory.protectedAt(new dev.modbench.api.WorldMemory.Pos(p.getX(),p.getY(),p.getZ()));
            if(!named.isEmpty())why="protected_region:"+String.join(",",named);
            Map<String,Object> row=new LinkedHashMap<>();row.put("pos",point(p));row.put("block",String.valueOf(net.minecraft.block.Block.blockRegistry.getNameForObject(world.getBlock(p.getX(),p.getY(),p.getZ()))));row.put("why",why);
            if(why.equals("will_not_break_here"))for(int[] d:new int[][]{{0,1,0},{1,0,0},{-1,0,0},{0,0,1},{0,0,-1}}){
                var beside=world.getBlock(p.getX()+d[0],p.getY()+d[1],p.getZ()+d[2]);
                if(beside.getMaterial().isLiquid()){row.put("beside",String.valueOf(net.minecraft.block.Block.blockRegistry.getNameForObject(beside)));row.put("at",List.of(p.getX()+d[0],p.getY()+d[1],p.getZ()+d[2]));break;}
            }
            out.add(row);
        });
        return out;
    }
    private static List<Integer> point(baritone.compat.BlockPos p){return List.of(p.getX(),p.getY(),p.getZ());}
}
