// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh;
import dev.modbench.api.ControlRegistry;
import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.process.IBaritoneProcess;
import baritone.api.utils.BlockOptionalMeta;
import dev.modbench.api.InputArbiter;
import dev.modbench.api.Navigation;
import net.minecraft.client.Minecraft;
import java.util.*;
import static baritone.gtnh.pathing.WorkSpec.*;

/** Transport lifetime and settings scope around source resource processes. */
final class ReferenceProcessJob implements Navigation.Job,PlansWhilePaused {
    private final Minecraft mc=Minecraft.getMinecraft();
    private final Baritone engine;
    private final Object world=mc.theWorld,player=mc.thePlayer;
    private final String scope=ControlRegistry.memory().memory().scope(),kind;
    private final Map<baritone.api.Settings.Setting<?>,Object> saved=new LinkedHashMap<>();
    private final int duration;
    private final IBaritoneProcess process;
    private final Goal goal;
    private InputArbiter.Lease lease;
    private String state="running",reason="";
    private int ticks;
    private boolean started;
    private final long initialCalculations;
    private final Map<String,Object> failure=new LinkedHashMap<>();
    private final baritone.gtnh.pathing.Stall stall;
    private final Set<String> movements=new LinkedHashSet<>();
    // A get_to_block target named by picked identity (item or ore) is found by this scan on the game thread, as mining's are.
    private final MiningObservation scan;
    // One named by registry id is found in the loaded chunks a slice a tick, unless the engine's cache keeps its kind.
    private final ChunkObservation loaded;
    // A farm's crops, soils, seeds, fertilizers and pickups: the caller's selectors or the defaults, all in the receipt.
    private final FarmPlan farm;
    // Only the goal process plans in a paused world: the others choose their goals in a tick that may also act.
    private final FirstTick first;
    ReferenceProcessJob(Baritone engine,Map<String,Object> params){
        this.engine=engine;kind=String.valueOf(params.get("process"));first=new FirstTick(engine,mc.thePlayer);
        duration=integer(params,"durationTicks",1200,1,72000);stall=WorkAccess.stall(params);
        goal=kind.equals("goal")?ReferenceGoals.parse(child(params,"goal")):null;
        var feet=engine.getPlayerContext().playerFeet();
        var center=params.containsKey("center")?pos(params.get("center")):new baritone.compat.BlockPos(feet.x,feet.y,feet.z);
        int radius=integer(params,"radius",24,1,64);
        var blockSpec=child(params,"block");
        boolean picked=kind.equals("get_to_block")&&(blockSpec.containsKey("item")||blockSpec.containsKey("ore"));
        if(picked)WorkAccess.validateBlockSelector(blockSpec);
        BlockOptionalMeta block=kind.equals("get_to_block")&&!picked?new BlockOptionalMeta(String.valueOf(blockSpec.get("id"))+(blockSpec.containsKey("meta")?":"+integer(blockSpec,"meta",0,0,15):"")):null;
        scan=picked?new MiningObservation(mc.theWorld,bounds(Map.of("min",List.of(center.getX()-radius,Math.max(1,center.getY()-16),center.getZ()-radius),"max",List.of(center.getX()+radius,Math.min(254,center.getY()+16),center.getZ()+radius))),List.of(blockSpec),List.of()):null;
        loaded=block!=null&&!(engine.getPlayerContext().worldData()!=null&&baritone.cache.CachedChunk.trackedBlocks().contains(block.getBlock()))?new ChunkObservation(block):null;
        farm=kind.equals("farm")?new FarmPlan(mc.theWorld,center,radius,params):null;
        process=switch(kind){case "goal"->engine.getCustomGoalProcess();case "explore"->engine.getExploreProcess();case "get_to_block"->engine.getGetToBlockProcess();case "farm"->engine.getFarmProcess();default->throw new IllegalArgumentException("process must be goal, explore, get_to_block or farm");};
        if(kind.equals("explore")&&engine.getWorldProvider().getCurrentWorld()==null)throw new IllegalArgumentException("exploration requires the server world identity and cache");
        var settings=Baritone.settings();
        for(var s:List.of(settings.allowBreak,settings.allowPlace,settings.exploreForBlocks,settings.rightClickContainerOnArrival,settings.enterPortal))saved.put(s,s.value);
        engine.getPathingBehavior().forceCancel();BlockRules.reset();engine.snags.reset();baritone.gtnh.pathing.Cost.reset();initialCalculations=engine.getPathingBehavior().calculationsStarted();
        try{
            lease=ControlRegistry.controls().arbiter().acquire("baritone-"+kind,this::cancel);
            settings.allowBreak.value=bool(params,"allowBreak",false);settings.allowPlace.value=bool(params,"allowPlace",false);
            settings.exploreForBlocks.value=bool(params,"exploreForBlocks",true);
            settings.rightClickContainerOnArrival.value=bool(params,"openOnArrival",false);settings.enterPortal.value=bool(params,"enterPortal",false);
            engine.overrideProtection=bool(params,"overrideProtection",false);engine.named=p->false;engine.positionAllowed=p->true;engine.explicitMiningTargets=()->s->false;
            engine.getInputOverrideHandler().attach(lease);engine.bsi=new baritone.utils.BlockStateInterface(engine.getPlayerContext());
            switch(kind){
                case "goal"->{
                    var refused=GoalRoom.refusal(goal,GoalRoom.of(engine,mc.theWorld,mc.thePlayer,settings.allowBreak.value),settings.allowPlace.value);
                    if(refused==null)engine.getCustomGoalProcess().setGoalAndPath(goal);else{failure.putAll(refused);finish("failed","goal_not_standable");}
                }
                case "explore"->engine.getExploreProcess().explore(center.getX(),center.getZ());
                case "get_to_block"->{if(scan==null&&loaded==null)engine.getGetToBlockProcess().getToBlock(block);}
                case "farm"->engine.getFarmProcess().farm(radius,center,farm);
            }
        }catch(RuntimeException failure){finish("failed","start_failed");throw failure;}
    }
    @Override public boolean planningWhilePaused(){return !done()&&kind.equals("goal")&&first.planning(ticks);}
    @Override public void planWhilePaused(){if(mc.theWorld==world&&mc.thePlayer==player&&lease.isActive())first.plan();}
    void tick(){
        if(done())return;
        if(WorkAccess.died(player)){finish("failed","player_died");return;}
        if(mc.theWorld!=world||mc.thePlayer!=player||!scope.equals(ControlRegistry.memory().memory().scope())){cancel("world_changed");return;}
        if(!lease.isActive()){cancel(WorkAccess.lost(lease,"control_lost"));return;}
        if(mc.currentScreen!=null&&!ControlRegistry.controls().ownsPlayerInventory(lease)){cancel("gui_open");return;}
        // Explore and farm have no end of their own: their duration running out is a pause, not a success; the others failed to arrive.
        if(ticks++>=duration){finish(kind.equals("farm")||kind.equals("explore")?"paused":"failed","timeout");return;}
        first.before(ticks,mc.thePlayer);
        // The first scan of the bounds stands still by design: the stall watch starts once there is something to walk to.
        if(scan!=null){
            scan.tick();
            if(scan.passes==0)return;
            if(!started){engine.getGetToBlockProcess().getToBlock(scan);started=true;}
        }
        if(loaded!=null){
            loaded.tick(()->baritone.compat.LoadedChunkIndex.capture((net.minecraft.client.multiplayer.ChunkProviderClient)mc.theWorld.getChunkProvider()),engine.getPlayerContext().playerFeet());
            if(loaded.waiting())return;
            if(!started){engine.getGetToBlockProcess().getToBlock(loaded);started=true;}
        }
        // A farm's work shows in the inventory (harvest in, seeds out); everything else only in new ground.
        var feet=engine.getPlayerContext().playerFeet();
        if(stall.tick(kind.equals("farm")?inventory():0,feet.x,feet.y,feet.z,WorkAccess.searchBudget(engine))){finish(stall.advanced()?"paused":"failed",stall.reason());return;}
        engine.tickStart();first.after(ticks);var current=engine.getPathingBehavior().getCurrent();
        if(engine.snags.failure()!=null){finish("failed",engine.snags.failure());return;}
        if(current!=null)current.getPath().movements().forEach(m->movements.add(m.getClass().getSimpleName()));
        if(!process.isActive()){
            boolean success=switch(kind){case "goal"->goal.isInGoal(engine.getPlayerContext().playerFeet());case "get_to_block"->engine.getGetToBlockProcess().arrived;case "explore"->engine.getExploreProcess().completed;default->false;};
            // Not arriving has a measured cause: the process's own give-up, a snag, or how the last search ended.
            String stopped=kind.equals("get_to_block")?engine.getGetToBlockProcess().stopReason:null;
            finish(success?"succeeded":"failed",success?"source_process_complete":stopped!=null?stopped:PathFailure.cause(engine,initialCalculations,kind.equals("goal")?goal:engine.getPathingBehavior().getGoal(),failure));
        }
    }
    private long inventory(){long sum=0;for(var s:mc.thePlayer.inventory.mainInventory)if(s!=null)sum=sum*31+s.stackSize*7919L+net.minecraft.item.Item.getIdFromItem(s.getItem());return sum;}
    private void finish(String state,String reason){
        if(done())return;this.state=state;this.reason=reason;
        engine.getPathingBehavior().forceCancel();engine.getInputOverrideHandler().release();saved.forEach(ReferenceSettings::copy);
        if(lease!=null)lease.close();
    }
    @Override public void cancel(String reason){finish("cancelled",reason);}
    @Override public boolean done(){return !state.equals("running");}
    @Override public boolean succeeded(){return state.equals("succeeded");}
    @Override public Map<String,Object> status(){
        var out=new LinkedHashMap<String,Object>();out.put("engine","baritone-1.2.19-source-port");out.put("action",kind);out.put("state",state);out.put("reason",reason);
        out.put("ticks",ticks);out.put("controlOwned",!done()&&lease!=null&&lease.isActive());out.put("scope",scope);out.put("movementTypes",List.copyOf(movements));out.put("stall",stall.status());out.put("snags",engine.snags.status());out.put("cost",baritone.gtnh.pathing.Cost.status());if(!failure.isEmpty())out.put("failure",failure);out.put("pathRules",BlockRules.applied());
        out.put("goal",String.valueOf(engine.getPathingBehavior().getGoal()));first.status(out);
        if(farm!=null){out.put("farmRules",farm.rules());out.put("farmSeen",farm.seen);}
        if(scan!=null){out.put("scanPasses",scan.passes);out.put("scanMatches",scan.observedLocations().size());}
        if(loaded!=null){out.put("scanPasses",loaded.passes);out.put("scanMatches",loaded.observedLocations().size());}
        return out;
    }
}
