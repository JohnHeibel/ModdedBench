// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh;

import baritone.Baritone;
import baritone.compat.BlockPos;
import baritone.gtnh.pathing.*;
import static baritone.gtnh.pathing.WorkSpec.*;
import java.util.*;

/** Journal, native selectors and action ownership around the actual upstream MineProcess. */
final class MiningProcess extends BulkJob {
    private final List<Map<String,Object>> items;
    private final int quantity,baseline;
    private final Bounds bounds;
    private final Baritone engine;
    private final MiningObservation observation;
    private final Map<baritone.api.Settings.Setting<?>,Object> scopedSettings=new LinkedHashMap<>();
    private boolean started;
    private Integer finalCount;
    private String finalGoal;
    private int inactiveTicks;
    private record DropLocation(int entityId,baritone.compat.BlockPos position){}
    private final Set<DropLocation> retriedDrops=new HashSet<>();
    private List<Map<String,Object>> diagnostics=List.of();
    private List<List<Integer>> lastKnown=List.of(),lastRejected=List.of();
    MiningProcess(BaritoneNavigation nav,WorkJournal journal,Map<String,Object> options){
        super(nav,journal,options);engine=nav.reference();
        var blocks=WorkAccess.selectors(params.get("blocks"));items=WorkAccess.itemSelectors(params.get("items"));
        quantity=integer(params,"quantity",1,1,1000000);
        BlockPos origin=WorkAccess.feet();int radius=integer(params,"radius",24,1,64);
        Map<String,Object> scan=params.containsKey("bounds")?child(params,"bounds"):Map.of("min",List.of(origin.getX()-radius,Math.max(1,origin.getY()-16),origin.getZ()-radius),"max",List.of(origin.getX()+radius,Math.min(254,origin.getY()+16),origin.getZ()+radius));
        bounds=bounds(scan);if(bounds.volume()>262144)throw new IllegalArgumentException("mining scan exceeds 262144 cells");
        journal.spec.put("bounds",scan);
        baseline=integer(journal.progress,"initialCount",WorkAccess.count(items),0,1000000);journal.progress.put("initialCount",baseline);
        observation=new MiningObservation(world,bounds,blocks,items);
    }
    @Override void begin(){
        super.begin();engine.getPathingBehavior().forceCancel();
        for(var setting:List.of(Baritone.settings().allowBreak,Baritone.settings().allowPlace,Baritone.settings().exploreForBlocks,Baritone.settings().legitMine))scopedSettings.put(setting,setting.value);
        Baritone.settings().allowBreak.value=allowBreak;Baritone.settings().allowPlace.value=allowPlace;
        // This action has explicit observation bounds. Exploration is a separate
        // process, not permission to start a branch mine when its bounds empty.
        Baritone.settings().exploreForBlocks.value=false;Baritone.settings().legitMine.value=false;
        engine.overrideProtection=override;engine.positionAllowed=p->true;
        engine.explicitMiningTargets=observation::capture;
        engine.getInputOverrideHandler().attach(lease);
    }
    int gained(){return Math.max(0,WorkAccess.count(items)-baseline);}
    @Override String phase(){return "reference_mine";}
    @Override void step(){
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
                return Map.<String,Object>of("pos",point(p),"matches",observation.has(s),"canHarvest",costs.toolSet.canHarvest(s),"bestHotbarSlot",costs.toolSet.getBestSlot(s,false),"miningCost",duration,
                    "breakSafetyBlocked",baritone.pathing.movement.MovementHelper.avoidBreaking(costs.bsi,p.getX(),p.getY(),p.getZ(),s),
                    "above",costs.get(p.getX(),p.getY()+1,p.getZ()).toString());
            }).toList();
            engine.getMineProcess().mine(0,observation);started=true;
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
            if(newDrop){engine.bsi=new baritone.utils.BlockStateInterface(engine.getPlayerContext());process.mine(0,observation);}
            if(!process.isActive()){
                state="awaiting_inventory";
                if(inactiveTicks>Math.max(20,(Baritone.settings().mineDropLoiterDurationMSThanksLouca.value+49)/50))finish("failed","no_remaining_reachable_targets_or_drops");
                return;
            }
        }
        inactiveTicks=0;
        engine.tickStart();
        if(process.isActive()){
            lastKnown=process.knownLocations().stream().map(MiningProcess::point).toList();
            lastRejected=process.rejectedLocations().stream().map(MiningProcess::point).toList();
        }
        state=engine.getInputOverrideHandler().isInputForcedDown(baritone.api.utils.input.Input.CLICK_LEFT)?"mining":"pathing";
    }
    @Override void releaseProcess(){
        finalCount=mc.thePlayer==player?WorkAccess.count(items):null;
        finalGoal=String.valueOf(engine.getPathingBehavior().getGoal());
        engine.getPathingBehavior().forceCancel();engine.getInputOverrideHandler().release();
        engine.explicitMiningTargets=()->s->false;
        scopedSettings.forEach(ReferenceSettings::copy);
    }
    @Override public Map<String,Object> status(){
        Map<String,Object> out=super.status();
        out.put("engine","baritone-1.2.19-source-port");out.put("process","MineProcess");
        out.put("quantity",quantity);out.put("initialCount",baseline);
        Integer count=done()?finalCount:mc.thePlayer==player?WorkAccess.count(items):null;
        out.put("currentCount",count);out.put("gained",count==null?null:Math.max(0,count-baseline));
        out.put("scanPasses",observation==null?0:observation.passes);out.put("scanCursor",observation==null?0:observation.cursor);
        out.put("scanVolume",bounds==null?0:bounds.volume());out.put("targets",lastKnown);out.put("rejected",lastRejected);
        out.put("initialTargetDiagnostics",diagnostics);
        if(engine!=null){
            var current=engine.getPathingBehavior().getCurrent();
            out.put("goal",done()?finalGoal:String.valueOf(engine.getPathingBehavior().getGoal()));
            out.put("path",current==null?List.of():current.getPath().positions().stream().map(MiningProcess::point).toList());
            out.put("planning",engine.getPathingBehavior().getInProgress().isPresent());
        }
        out.put("completionMeaning","net matching inventory gain since this job began, including across explicit resume");return out;
    }
    private static List<Integer> point(baritone.compat.BlockPos p){return List.of(p.getX(),p.getY(),p.getZ());}
}
