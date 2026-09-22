// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh;

import baritone.compat.Registry;
import dev.modbench.api.ControlRegistry;
import baritone.compat.BlockPos;
import baritone.gtnh.pathing.*;
import dev.modbench.api.WorldMemory;
import dev.modbench.api.Navigation;
import java.util.*;
import net.minecraft.client.Minecraft;

/** Navigation provider: every route runs through the upstream PathingBehavior/PathExecutor of one Baritone instance. */
public final class BaritoneNavigation implements Navigation {
    private final baritone.Baritone reference=new baritone.Baritone();
    private final ReferenceApiSession apiSession=new ReferenceApiSession(reference);
    baritone.Baritone reference(){return reference;}
    private final Minecraft mc=Minecraft.getMinecraft();
    private Job active;
    private static final List<String> MOVEMENT=List.of("walk","step_up","step_down","ascend","descend","climb_up","climb_down","climb_enter","climb_exit","swim","swim_enter","swim_exit","swim_up");
    @Override public Map<String,Object> inspectTerrain(int x,int y,int z) {
        if(mc.theWorld==null||mc.thePlayer==null||y<1||y>254||!ForgeSnapshot.loaded(mc.theWorld,x,y,z)) throw new IllegalArgumentException("terrain cell is not loaded");
        BlockPos p=new BlockPos(x,y,z);TerrainGrid terrain=ForgeSnapshot.local(mc.theWorld,p,p);
        Map<String,Object> out=new LinkedHashMap<>();
        double height=terrain.standingY(p);
        out.put("pos",List.of(x,y,z));out.put("standingY",Double.isFinite(height)?height:null);
        out.put("traversable",terrain.traversable(x,y,z));out.put("climbable",terrain.climbable(p));out.put("ladderFacing",terrain.ladder(p).name());
        out.put("collisionBoxes",terrain.boxes(x,y,z));out.put("belowCollisionBoxes",terrain.boxes(x,y-1,z));
        var node=baritone.compat.NavigationCoordinates.goal(reference.getPlayerContext().world(),new baritone.compat.BlockPos(x,y,z));
        out.put("sourceEngineNode",List.of(node.getX(),node.getY(),node.getZ()));
        out.put("traversableMeaning","native collision observation; source-engine movement rules also apply");
        return out;
    }
    @Override public Map<String,Object> inspectFluid(int x,int y,int z) {
        if(mc.theWorld==null) throw new IllegalArgumentException("player is not in a world");
        return ForgeFluids.inspect(mc.theWorld,x,y,z);
    }
    @Override public Job mineBlock(int x,int y,int z,int timeoutTicks) {
        return mineBlock(x,y,z,timeoutTicks,true);
    }
    @Override public Job mineBlock(int x,int y,int z,int timeoutTicks,boolean autoTool) {
        return mineBlock(x,y,z,timeoutTicks,autoTool,false);
    }
    @Override public Job mineBlock(int x,int y,int z,int timeoutTicks,boolean autoTool,boolean overrideProtection) {
        if(active!=null && !active.done()) active.cancel("superseded");
        active=new MiningJob(mc,new BlockPos(x,y,z),timeoutTicks,autoTool,overrideProtection);
        return active;
    }
    @Override public Job placeBlock(int x,int y,int z,int timeoutTicks) {
        return placeBlock(x,y,z,timeoutTicks,false);
    }
    @Override public Job placeBlock(int x,int y,int z,int timeoutTicks,boolean overrideProtection) {
        if(active!=null && !active.done()) active.cancel("superseded");
        active=new PlacingJob(new BlockPos(x,y,z),timeoutTicks,overrideProtection);return active;
    }
    @Override public Map<String,Object> inspectTools(int x,int y,int z) {
        if(mc.theWorld==null||mc.thePlayer==null) throw new IllegalArgumentException("player required");
        var result=MiningTools.inspect(mc.theWorld,new BlockPos(x,y,z));
        var state=reference.getPlayerContext().world().getBlockState(new baritone.compat.BlockPos(x,y,z));
        var tools=new baritone.utils.ToolSet(mc.thePlayer);
        double strength=tools.getStrVsBlock(state);
        result.put("sourceEngine",Map.of("bestHotbarSlot",tools.getBestSlot(state,false),"canHarvest",tools.canHarvest(state),"estimatedStrength",Double.isFinite(strength)?strength:0));
        return result;
    }
    @Override public Job goTo(int x,int y,int z,int timeoutTicks) {
        return goTo(x,y,z,timeoutTicks,false,false);
    }
    @Override public Job goTo(int x,int y,int z,int timeoutTicks,boolean allowBreak,boolean allowPlace) {
        return goTo(x,y,z,timeoutTicks,allowBreak,allowPlace,false);
    }
    @Override public Job goTo(int x,int y,int z,int timeoutTicks,boolean allowBreak,boolean allowPlace,boolean overrideProtection) {
        if(mc.thePlayer==null || mc.theWorld==null || mc.currentScreen!=null) throw new IllegalArgumentException("navigation needs a player with GUI closed");
        if(mc.thePlayer.getHealth()<=0 || (!mc.thePlayer.onGround && !mc.thePlayer.isInWater() && !mc.thePlayer.isOnLadder())) throw new IllegalArgumentException("navigation must start alive, grounded, on a ladder or in water");
        BlockPos start=feet();
        if(!GoalRange.validGoal(start,new BlockPos(x,y,z))) throw new IllegalArgumentException("goal must be within 4096 horizontal blocks and world height");
        if(timeoutTicks<1 || timeoutTicks>72000) throw new IllegalArgumentException("timeoutTicks must be 1..72000");
        if(active!=null && !active.done()) active.cancel("superseded");
        active=new ReferenceNavigationJob(reference,List.of(new BlockPos(x,y,z)),timeoutTicks,allowBreak,allowPlace,overrideProtection,null,null,0);
        return active;
    }
    @Override public Job route(String name,boolean reverse,int startIndex,int timeoutTicks,boolean allowBreak,boolean allowPlace,boolean overrideProtection) {
        var route=ControlRegistry.memory().memory().snapshot().routes().get(name);
        if(route==null) throw new IllegalArgumentException("unknown saved route");
        if(mc.thePlayer==null || mc.currentScreen!=null || mc.thePlayer.getHealth()<=0) throw new IllegalArgumentException("route needs a living player with GUI closed");
        if(timeoutTicks<1||timeoutTicks>72000) throw new IllegalArgumentException("timeoutTicks must be 1..72000");
        List<WorldMemory.Pos> points=new ArrayList<>(route.points());if(reverse) Collections.reverse(points);
        if(startIndex<0||startIndex>=points.size()) throw new IllegalArgumentException("startIndex is outside saved route");
        if(active!=null&&!active.done()) active.cancel("superseded");
        active=new RouteRun(name,points,startIndex,route.radius(),timeoutTicks,allowBreak,allowPlace,overrideProtection);return active;
    }
    @Override public Map<String,Object> status() {
        var out=new LinkedHashMap<String,Object>(apiSession.active()?apiSession.status():active==null?Map.of("state","idle","available",true,"movement",MOVEMENT,"mining",true,"automaticTools",true,"excavation",true,"placement",true):active.status());
        out.put("nativeEvents",baritone.compat.NativeEvents.diagnostics());out.put("javaApi",apiSession.status());
        out.put("lastCalculation",reference.getPathingBehavior().lastCalculation());return out;
    }
    @Override public void cancel(String reason){if(active!=null&&!active.done())active.cancel(reason);apiSession.stop(reason);}
    void stop(){cancel("cancelled");}
    void runApi(java.util.function.Consumer<baritone.api.IBaritone> activation){
        WorkAccess.player();stop();activation.accept(reference);
    }
    @Override public Map<String,Object> settings(Map<String,Object> params){return ReferenceSettings.call(reference,params);}
    @Override public Map<String,Object> cache(Map<String,Object> params){return ReferenceCache.call(reference,params);}
    @Override public Job follow(Map<String,Object> params){
        WorkAccess.player();
        if(active!=null&&!active.done())active.cancel("superseded");
        active=new ReferenceFollowJob(reference,params);return active;
    }
    @Override public Job fight(Map<String,Object> params){
        WorkAccess.player();
        if(active!=null&&!active.done())active.cancel("superseded");
        active=new FightJob(reference,params);return active;
    }
    @Override public Job sourceProcess(Map<String,Object> params){
        WorkAccess.player();if(active!=null&&!active.done())active.cancel("superseded");
        active=new ReferenceProcessJob(reference,params);return active;
    }
    @Override public Job mine(Map<String,Object> params) {return process(new WorkJournal("mine",params),params);}
    @Override public Job build(Map<String,Object> params) {params=ConstructionPlans.resolve(params);return process(new WorkJournal("build",params),params);}
    @Override public Map<String,Object> stageBuild(Map<String,Object> params){return ConstructionPlans.stage(params);}
    @Override public Map<String,Object> pauseBuild() {
        if(!(active instanceof BulkJob job)||!job.journal.kind.equals("build"))throw new IllegalArgumentException("no construction process");
        job.finish("paused","requested_by_model");return job.status();
    }
    @Override public Map<String,Object> buildMaterials() {
        WorkAccess.player();List<Map<String,Object>> stacks=new ArrayList<>();
        for(int i=0;i<36;i++){var stack=mc.thePlayer.inventory.mainInventory[i];if(stack==null||!(stack.getItem() instanceof net.minecraft.item.ItemBlock))continue;
            var row=new LinkedHashMap<>(InventorySelection.describe(stack));row.put("slot",i);row.put("hotbar",i<9);row.put("blockId",Registry.name(net.minecraft.block.Block.getBlockFromItem(stack.getItem())));row.put("initialItemMeta",stack.getItem().getMetadata(stack.getItemDamage()));row.put("placement",dev.modbench.api.ControlRegistry.placement().describe(stack));stacks.add(row);}
        return Map.of("approxPlaceable",stacks,"meaning","native ItemBlock mappings and initial metadata; final state depends on placement side, hit, pose and native callbacks");
    }
    @Override public Job resume(String jobId,Map<String,Object> options) {
        if(!Set.of("jobId","timeoutTicks","overrideProtection","allowBreak","allowPlace").containsAll(options.keySet()))throw new IllegalArgumentException("resume accepts jobId, timeoutTicks, overrideProtection, allowBreak and allowPlace; create a new job to change its plan");
        return process(new WorkJournal(jobId),options);
    }
    private Job process(WorkJournal journal,Map<String,Object> options) {
        WorkAccess.player();
        if(active!=null&&!active.done())active.cancel("superseded");
        BulkJob job=journal.kind.equals("mine")?new MiningProcess(this,journal,options):journal.kind.equals("build")?new ReferenceConstructionProcess(this,journal,options):null;
        if(job==null)throw new IllegalArgumentException("unknown journal work kind");
        active=job;try{job.begin();}catch(Exception error){job.cancel("start_failed");throw error;}return job;
    }
    @Override public Map<String,Object> workStatus(String id) {var data=WorkJournal.status(id);if(!Objects.equals(data.get("scope"),ControlRegistry.memory().memory().scope()))throw new IllegalArgumentException("work belongs to another world/dimension");return data;}
    @Override public Map<String,Object> previewBuild(Map<String,Object> params){params=ConstructionPlans.resolve(params);WorkAccess.player();return new ConstructionPlan(params,new LinkedHashMap<>(),mc.theWorld).preview(WorkSpec.bool(params,"overrideProtection",false));}
    @Override public Map<String,Object> importSchematic(Map<String,Object> params){try{return PlanImport.schematic(params);}catch(java.io.IOException error){throw new IllegalArgumentException("schematic: "+error.getMessage(),error);}}
    @Override public Map<String,Object> copy(Map<String,Object> params){WorkAccess.player();return PlanImport.copy(mc.theWorld,params);}
    @Override public Map<String,Object> scan(Map<String,Object> params) {
        WorkAccess.player();var bounds=WorkSpec.bounds(WorkSpec.child(params,"bounds"));if(bounds.volume()>262144)throw new IllegalArgumentException("scan volume exceeds 262144");
        var selectors=params.containsKey("blocks")?WorkAccess.selectors(params.get("blocks")):null;int cursor=WorkSpec.integer(params,"cursor",0,0,(int)bounds.volume()),limit=WorkSpec.integer(params,"limit",64,1,256),budget=WorkSpec.integer(params,"budget",2048,1,4096);
        List<Map<String,Object>> found=new ArrayList<>();int start=cursor,unloaded=0;
        while(cursor<bounds.volume()&&cursor-start<budget&&found.size()<limit){
            BlockPos p=bounds.at(cursor++);if(!ForgeSnapshot.loaded(mc.theWorld,p.getX(),p.getY(),p.getZ())){unloaded++;continue;}
            if(selectors==null||WorkAccess.block(mc.theWorld,p,selectors)){
                var row=WorkAccess.observed(mc.theWorld,p);var picked=WorkAccess.picked(mc.theWorld,p);
                row.put("pickedItem",InventorySelection.describe(picked));
                // A native picked variant is a material only if its ItemBlock
                // actually places this block. Non-block picks (seeds, tools,
                // debug items) are not silently turned into placement promises.
                if(picked!=null&&picked.getItem() instanceof net.minecraft.item.ItemBlock&&
                    net.minecraft.block.Block.getBlockFromItem(picked.getItem())==mc.theWorld.getBlock(p.getX(),p.getY(),p.getZ()))
                    row.put("placementItem",InventorySelection.describe(picked));
                found.add(row);
            }
        }
        Map<String,Object> out=new LinkedHashMap<>();out.put("matches",found);out.put("cursor",cursor);out.put("done",cursor==bounds.volume());out.put("scanned",cursor-start);out.put("unloaded",unloaded);out.put("volume",bounds.volume());return out;
    }
    void tickChild(Job job) {if(job instanceof ReferenceProcessJob process)process.tick();else if(job instanceof ReferenceFollowJob follow)follow.tick();else if(job instanceof FightJob fight)fight.tick();else if(job instanceof ReferenceNavigationJob run)run.tick();else if(job instanceof MiningJob mine)mine.tick();else if(job instanceof PlacingJob place)place.tick();}
    void afterTick(){reference.tickEnd();}
    void tick() {
        reference.getWorldProvider().tick();
        if(active!=null && !active.done()) {
            try { if(active instanceof BulkJob work){work.symptoms.sample(mc.thePlayer);work.tick();}else if(active instanceof RouteRun route) route.tick();else tickChild(active); }
            catch(Exception e) {
                String reason="game_error: "+e.getClass().getSimpleName()+": "+e.getMessage();
                if(active instanceof RouteRun route) route.finish("failed",reason);else if(active instanceof ReferenceNavigationJob run)run.finish("failed",reason); else active.cancel(reason);
            }
        }else apiSession.tick();
    }
    private BlockPos feet() {
        BlockPos p=new BlockPos((int)Math.floor(mc.thePlayer.posX),(int)Math.floor(mc.thePlayer.boundingBox.minY+.001),(int)Math.floor(mc.thePlayer.posZ));
        if(mc.thePlayer.onGround && ForgeSnapshot.classify(mc.theWorld,p.getX(),p.getY(),p.getZ())==TerrainGrid.PARTIAL) {
            TerrainGrid local=ForgeSnapshot.local(mc.theWorld,p,p);
            if(!Double.isFinite(local.standingY(p))) {
                BlockPos raised=new BlockPos(p.getX(),p.getY()+1,p.getZ());
                double height=local.standingY(raised);
                if(Double.isFinite(height) && height-mc.thePlayer.boundingBox.minY<=mc.thePlayer.stepHeight+.001) return raised;
            }
        }
        BlockPos below=new BlockPos(p.getX(),p.getY()-1,p.getZ());
        return !mc.thePlayer.onGround && !ForgeSnapshot.water(mc.theWorld,p) && ForgeSnapshot.water(mc.theWorld,below)?below:p;
    }

    /** Ordered reusable route; each leg still plans and revalidates actual terrain. */
    private final class RouteRun implements Job {
        final String name,scope=ControlRegistry.memory().memory().scope();
        final List<WorldMemory.Pos> points;
        final double radius;
        final boolean allowBreak,allowPlace,overrideProtection;
        final int startedAt;
        int next,remaining,ticks,completed;
        String state="following_route",reason="";
        ReferenceNavigationJob leg;
        RouteRun(String name,List<WorldMemory.Pos> points,int start,double radius,int timeout,boolean allowBreak,boolean allowPlace,boolean overrideProtection) {
            this.name=name;this.points=List.copyOf(points);this.next=start;this.startedAt=start;this.radius=radius;this.remaining=timeout;
            this.allowBreak=allowBreak;this.allowPlace=allowPlace;this.overrideProtection=overrideProtection;
        }
        void tick() {
            if(done()) return;
            if(!scope.equals(ControlRegistry.memory().memory().scope())) {cancel("world_or_dimension_changed");return;}
            if(--remaining<=0) {finish("failed","timeout");return;}ticks++;
            if(leg==null) {
                if(next>=points.size()) {finish("succeeded","route_complete");return;}
                WorldMemory.Pos p=points.get(next);BlockPos goal=new BlockPos(p.x(),p.y(),p.z());
                if(!GoalRange.validGoal(feet(),goal)) {finish("failed","route_leg_out_of_range");return;}
                WorldMemory.Pos prior=next==startedAt?null:points.get(next-1);
                leg=new ReferenceNavigationJob(reference,List.of(goal),remaining,allowBreak,allowPlace,overrideProtection,null,prior==null?null:new BlockPos(prior.x(),prior.y(),prior.z()),radius);
            }
            leg.tick();
            if(leg.done()) {
                if(!leg.succeeded()) {finish(leg.status().get("state").equals("cancelled")?"cancelled":"failed","route_leg_"+next+":"+leg.status().get("reason"));return;}
                completed++;next++;leg=null;
            }
        }
        void finish(String state,String reason) {if(done()) return;this.state=state;this.reason=reason;if(leg!=null&&!leg.done()) leg.cancel(reason);}
        @Override public boolean done() {return state.equals("succeeded")||state.equals("failed")||state.equals("cancelled");}
        @Override public boolean succeeded() {return state.equals("succeeded");}
        @Override public void cancel(String reason) {finish("cancelled",reason);}
        @Override public Map<String,Object> status() {
            Map<String,Object> out=new LinkedHashMap<>();out.put("action","route");out.put("name",name);out.put("state",state);out.put("reason",reason);
            out.put("nextIndex",next);out.put("completedAnchors",completed);out.put("totalAnchors",points.size());out.put("ticks",ticks);out.put("scope",scope);out.put("radius",radius);
            out.put("overrideProtection",overrideProtection);out.put("leg",leg==null?null:leg.status());return out;
        }
    }
}
