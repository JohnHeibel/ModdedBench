// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh;

import dev.modbench.api.ControlRegistry;
import baritone.gtnh.pathing.*;
import dev.modbench.api.WorldMemory;
import dev.modbench.api.InputArbiter;
import dev.modbench.api.Navigation;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.world.World;

/** Game-thread executor over the retained Baritone search core and Forge terrain adapter. */
public final class BaritoneNavigation implements Navigation {
    private final baritone.Baritone reference=new baritone.Baritone();
    private final ReferenceApiSession apiSession=new ReferenceApiSession(reference);
    baritone.Baritone reference(){return reference;}
    private final Minecraft mc=Minecraft.getMinecraft();
    private final ExecutorService search=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"baritone-gtnh-search");t.setDaemon(true);return t;});
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
        active=new MiningJob(mc,new BlockPos(x,y,z),timeoutTicks,autoTool,false,null,overrideProtection);
        return active;
    }
    @Override public Job placeBlock(int x,int y,int z,int timeoutTicks) {
        return placeBlock(x,y,z,timeoutTicks,false);
    }
    @Override public Job placeBlock(int x,int y,int z,int timeoutTicks,boolean overrideProtection) {
        if(active!=null && !active.done()) active.cancel("superseded");
        active=new PlacingJob(new BlockPos(x,y,z),timeoutTicks,null,overrideProtection);return active;
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
        if(!RouteProgress.validGoal(start,new BlockPos(x,y,z))) throw new IllegalArgumentException("goal must be within 4096 horizontal blocks and world height");
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
            var row=new LinkedHashMap<>(InventorySelection.describe(stack));row.put("slot",i);row.put("hotbar",i<9);row.put("blockId",net.minecraft.block.Block.blockRegistry.getNameForObject(net.minecraft.block.Block.getBlockFromItem(stack.getItem())));row.put("initialItemMeta",stack.getItem().getMetadata(stack.getItemDamage()));row.put("placement",dev.modbench.api.ControlRegistry.placement().describe(stack));stacks.add(row);}
        return Map.of("approxPlaceable",stacks,"meaning","native ItemBlock mappings and initial metadata; final state depends on placement side, hit, pose and native callbacks");
    }
    @Override public Job resume(String jobId,Map<String,Object> options) {
        if(!Set.of("jobId","timeoutTicks","overrideProtection","allowBreak","allowPlace").containsAll(options.keySet()))throw new IllegalArgumentException("resume accepts jobId, timeoutTicks, overrideProtection, allowBreak and allowPlace; create a new job to change its plan");
        return process(new WorkJournal(jobId),options);
    }
    private Job process(WorkJournal journal,Map<String,Object> options) {
        WorkAccess.player();
        if(active!=null&&!active.done())active.cancel("superseded");
        BulkJob job=journal.kind.equals("mine")?new MiningProcess(this,journal,options):journal.kind.equals("build")?("builder".equals(journal.spec.get("mode"))?new ReferenceConstructionProcess(this,journal,options):new BuildingProcess(this,journal,options)):null;
        if(job==null)throw new IllegalArgumentException("unknown journal work kind");
        active=job;try{job.begin();}catch(Exception error){job.cancel("start_failed");throw error;}return job;
    }
    @Override public Map<String,Object> workStatus(String id) {var data=WorkJournal.status(id);if(!Objects.equals(data.get("scope"),ControlRegistry.memory().memory().scope()))throw new IllegalArgumentException("work belongs to another world/dimension");return data;}
    @Override public Map<String,Object> previewBuild(Map<String,Object> params){params=ConstructionPlans.resolve(params);if(!"builder".equals(params.get("mode")))return BuildingProcess.preview(params);WorkAccess.player();var process=new ConstructionProcess(this,new WorkJournal("build",params),params);var pending=process.cells.stream().filter(c->!process.correct(c)).map(process::desired).toList();var out=BuildingProcess.inspect(pending,true,process.override);out.put("mode","builder");out.put("selected",process.cells.size());out.put("acceptedBySchematic",process.cells.size()-pending.size());out.put("settings",process.settings.values);return out;}
    @Override public Map<String,Object> importSchematic(Map<String,Object> params){try{return PlanImport.schematic(params);}catch(java.io.IOException error){throw new IllegalArgumentException("schematic: "+error.getMessage(),error);}}
    @Override public Map<String,Object> copy(Map<String,Object> params){WorkAccess.player();return PlanImport.copy(mc.theWorld,params);}
    @Override public Map<String,Object> scan(Map<String,Object> params) {
        WorkAccess.player();var bounds=WorkSpec.bounds(WorkSpec.child(params,"bounds"));if(bounds.volume()>262144)throw new IllegalArgumentException("scan volume exceeds 262144");
        var selectors=params.containsKey("blocks")?WorkAccess.selectors(params.get("blocks")):null;int cursor=WorkSpec.integer(params,"cursor",0,0,(int)bounds.volume()),limit=WorkSpec.integer(params,"limit",64,1,256),budget=WorkSpec.integer(params,"budget",2048,1,4096);
        List<Map<String,Object>> found=new ArrayList<>();int start=cursor,unloaded=0;
        while(cursor<bounds.volume()&&cursor-start<budget&&found.size()<limit){
            BlockPos p=bounds.at(cursor++);if(!ForgeSnapshot.loaded(mc.theWorld,p.x(),p.y(),p.z())){unloaded++;continue;}
            if(selectors==null||WorkAccess.block(mc.theWorld,p,selectors)){
                var row=WorkAccess.observed(mc.theWorld,p);var picked=WorkAccess.picked(mc.theWorld,p);
                row.put("pickedItem",InventorySelection.describe(picked));
                // A native picked variant is a material only if its ItemBlock
                // actually places this block. Non-block picks (seeds, tools,
                // debug items) are not silently turned into placement promises.
                if(picked!=null&&picked.getItem() instanceof net.minecraft.item.ItemBlock&&
                    net.minecraft.block.Block.getBlockFromItem(picked.getItem())==mc.theWorld.getBlock(p.x(),p.y(),p.z()))
                    row.put("placementItem",InventorySelection.describe(picked));
                found.add(row);
            }
        }
        Map<String,Object> out=new LinkedHashMap<>();out.put("matches",found);out.put("cursor",cursor);out.put("done",cursor==bounds.volume());out.put("scanned",cursor-start);out.put("unloaded",unloaded);out.put("volume",bounds.volume());return out;
    }
    Navigation.Job travel(BlockPos p,int ticks,boolean allowBreak,boolean allowPlace,boolean override,InputArbiter.Lease parent) {return new ReferenceNavigationJob(reference,List.of(p),ticks,allowBreak,allowPlace,override,parent,null,0);}
    Navigation.Job travel(List<BlockPos> goals,int ticks,boolean allowBreak,boolean allowPlace,boolean override,InputArbiter.Lease parent) {return new ReferenceNavigationJob(reference,goals,ticks,allowBreak,allowPlace,override,parent,null,0);}
    Navigation.Job travelConstruction(List<BlockPos> goals,int ticks,ConstructionProcess builder) {return new Run(goals,ticks,builder.allowBreak,builder.allowPlace,builder.override,null,0,builder.lease,builder);}
    void tickChild(Job job) {if(job instanceof ReferenceProcessJob process)process.tick();else if(job instanceof ReferenceFollowJob follow)follow.tick();else if(job instanceof ReferenceNavigationJob run)run.tick();else if(job instanceof Run run)run.tick();else if(job instanceof MiningJob mine)mine.tick();else if(job instanceof ExactPlacementJob place)place.tick();else if(job instanceof PlacingJob place)place.tick();}
    void afterTick(){reference.tickEnd();}
    void tick() {
        reference.getWorldProvider().tick();
        if(active!=null && !active.done()) {
            try { if(active instanceof BulkJob work)work.tick();else if(active instanceof RouteRun route) route.tick();else tickChild(active); }
            catch(Exception e) {
                String reason="game_error: "+e.getClass().getSimpleName()+": "+e.getMessage();
                if(active instanceof RouteRun route) route.finish("failed",reason);else if(active instanceof ReferenceNavigationJob run)run.finish("failed",reason);else if(active instanceof Run run) run.finish("failed",reason); else active.cancel(reason);
            }
        }else apiSession.tick();
    }
    private BlockPos feet() {
        BlockPos p=new BlockPos((int)Math.floor(mc.thePlayer.posX),(int)Math.floor(mc.thePlayer.boundingBox.minY+.001),(int)Math.floor(mc.thePlayer.posZ));
        if(mc.thePlayer.onGround && ForgeSnapshot.classify(mc.theWorld,p.x(),p.y(),p.z())==TerrainGrid.PARTIAL) {
            TerrainGrid local=ForgeSnapshot.local(mc.theWorld,p,p);
            if(!Double.isFinite(local.standingY(p))) {
                BlockPos raised=new BlockPos(p.x(),p.y()+1,p.z());
                double height=local.standingY(raised);
                if(Double.isFinite(height) && height-mc.thePlayer.boundingBox.minY<=mc.thePlayer.stepHeight+.001) return raised;
            }
        }
        BlockPos below=new BlockPos(p.x(),p.y()-1,p.z());
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
                if(!RouteProgress.validGoal(feet(),goal)) {finish("failed","route_leg_out_of_range");return;}
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

    private final class Run implements Job {
        BlockPos goal;
        final List<BlockPos> goals;
        final Goal searchGoal;
        final RouteProgress progress=new RouteProgress();
        final boolean allowBreak,allowPlace,overrideProtection;
        final BlockPos corridorStart;
        final double corridorRadius;
        WorkWorld workWorld;
        Navigation.Job workJob;
        final List<Map<String,Object>> completedWork=new ArrayList<>();
        int mined,placed;
        World world=mc.theWorld;
        Object player=mc.thePlayer;
        final AtomicBoolean cancelled=new AtomicBoolean();
        InputArbiter.Lease lease;
        final boolean ownsLease;
        final ConstructionProcess construction;
        ForgeSnapshot capture;
        Future<SearchResult> pending;
        SearchResult result;
        List<Double> pathFeetY=List.of();
        TerrainGrid planned;
        String state="capturing",reason="";
        int remaining,age,index,calculations,replans,recoveryReplans,stalled,settled;
        Map<String,Object> arrival;
        double bestDistance=Double.POSITIVE_INFINITY;
        final float initialHealth=mc.thePlayer.getHealth();
        boolean recoveringAir;
        boolean wideCapture;
        int captureTicks,capturedCells;
        Run(BlockPos goal,int timeoutTicks,boolean allowBreak,boolean allowPlace,boolean overrideProtection,BlockPos corridorStart,double corridorRadius) {
            this(goal,timeoutTicks,allowBreak,allowPlace,overrideProtection,corridorStart,corridorRadius,null);
        }
        Run(BlockPos goal,int timeoutTicks,boolean allowBreak,boolean allowPlace,boolean overrideProtection,BlockPos corridorStart,double corridorRadius,InputArbiter.Lease parent) {
            this(List.of(goal),timeoutTicks,allowBreak,allowPlace,overrideProtection,corridorStart,corridorRadius,parent);
        }
        Run(List<BlockPos> goals,int timeoutTicks,boolean allowBreak,boolean allowPlace,boolean overrideProtection,BlockPos corridorStart,double corridorRadius,InputArbiter.Lease parent) {
            this(goals,timeoutTicks,allowBreak,allowPlace,overrideProtection,corridorStart,corridorRadius,parent,null);
        }
        Run(List<BlockPos> goals,int timeoutTicks,boolean allowBreak,boolean allowPlace,boolean overrideProtection,BlockPos corridorStart,double corridorRadius,InputArbiter.Lease parent,ConstructionProcess construction) {
            this.construction=construction;
            if(goals.isEmpty()||goals.size()>256)throw new IllegalArgumentException("1..256 work goals required");
            if(goals.size()>1&&corridorStart!=null)throw new IllegalArgumentException("corridors require a single route anchor");
            this.goals=List.copyOf(goals);this.searchGoal=construction==null?new GoalComposite(goals.stream().map(p->new GoalBlock(p.x(),p.y(),p.z())).toList()):construction.searchGoal();
            this.allowBreak=allowBreak;this.allowPlace=allowPlace;this.overrideProtection=overrideProtection;this.corridorStart=corridorStart;this.corridorRadius=corridorRadius;
            this.goal=goals.get(0); remaining=timeoutTicks;
            ownsLease=parent==null;lease=ownsLease?ControlRegistry.controls().arbiter().acquire("baritone",this::cancel,overrideProtection,true):parent;
            if(!lease.isActive()) {finish("cancelled","input_unavailable");return;}
            recalculate();
        }
        void recalculate() {
            stabilize();
            capture=ForgeSnapshot.forGoals(world,feet(),goals,allowBreak,wideCapture);
            state="capturing"; pending=null; result=null; planned=null;pathFeetY=List.of();index=0;stalled=0;settled=0;bestDistance=Double.POSITIVE_INFINITY;
        }
        void tick() {
            age++;
            if(mc.theWorld!=world || mc.thePlayer!=player) { cancel("world_changed"); return; }
            if(!lease.isActive()) { cancel("superseded"); return; }
            if(mc.currentScreen!=null&&!ControlRegistry.controls().ownsPlayerInventory(lease) || mc.thePlayer.getHealth()<=0) { cancel("gui_or_death"); return; }
            if(--remaining<=0) { finish("failed","timeout");return; }
            if(mc.thePlayer.getHealth()<initialHealth || mc.thePlayer.isBurning()) {finish("failed","damage_or_fire");return;}
            if(!ForgeSnapshot.safeBody(world,mc.thePlayer.posX,mc.thePlayer.boundingBox.minY,mc.thePlayer.posZ,true)) {finish("failed","hazard_contact");return;}
            if(mc.thePlayer.getAir()<80 && mc.thePlayer.isInWater()) recoveringAir=true;
            if(recoveringAir) {
                stabilize();
                if(mc.thePlayer.getAir()>=280) {recoveringAir=false;recalculate();}
                return;
            }
            if(workJob!=null) {
                tickChild(workJob);
                if(workJob.done()) {
                    Map<String,Object> workStatus=workJob.status();
                    if(completedWork.size()>=256) completedWork.remove(0);completedWork.add(workStatus);
                    if(!workJob.succeeded()) {finish("failed","work_failed: "+workStatus.get("reason"));return;}
                    if(workJob instanceof MiningJob) mined++;else placed++;
                    workJob=null;state="moving";
                }
                return;
            }
            if(state.equals("capturing")) {
                stabilize();
                captureTicks++;
                if(!capture.captureSlice()) return;
                capturedCells+=capture.cellsCaptured();
                TerrainGrid snapshot=capture.finish();planned=snapshot; BlockPos start=feet();
                WorldMemory.Snapshot protection=allowBreak||allowPlace?ControlRegistry.memory().memory().snapshot():null;
                workWorld=new WorkWorld(snapshot,capture.breakCosts(),allowPlace?(construction==null?PlacementItems.count():construction.placementBudget()):0,
                    p->p.y()>=1&&p.y()<=254&&(overrideProtection||protection==null||protection.protectedAt(new WorldMemory.Pos(p.x(),p.y(),p.z())).isEmpty()),construction==null?null:construction.costs(snapshot));
                if(!snapshot.traversable(start.x(),start.y(),start.z())) {finish("failed","unsupported_start");return;}
                calculations++; state="planning";
                WorldView terrainWorld=allowBreak||allowPlace?workWorld:snapshot;
                WorldView searchWorld=corridorStart==null?terrainWorld:new CorridorWorld(terrainWorld,corridorStart,goal,corridorRadius);
                pending=search.submit(()->new AStarPathFinder(searchWorld,start,searchGoal).calculate(250,1000,60000,cancelled::get));
                return;
            }
            if(state.equals("planning")) {
                stabilize();
                if(!pending.isDone()) return;
                try {result=pending.get();} catch(Exception e) {finish("failed","search_error: "+e);return;}
                if(result.status()!=SearchResult.Status.GOAL && capture.localPlan && !wideCapture){wideCapture=true;recalculate();return;}
                if(result.status()!=SearchResult.Status.GOAL && !usablePartial()) {finish("failed",result.status().name().toLowerCase(Locale.ROOT));return;}
                if(result.status()==SearchResult.Status.GOAL)goal=result.path().get(result.path().size()-1);
                if(allowPlace && workWorld.placementsRequired(result.path())>(construction==null?PlacementItems.count():construction.placementBudget())) {finish("failed","insufficient_placement_material");return;}
                pathFeetY=result.path().stream().map(p->allowBreak||allowPlace?workWorld.feetY(p):planned.feetY(p)).toList();
                // PathExecutor executes the first edge from the actual starting
                // pose. The first node is not a separate movement destination:
                // centering there can deadlock a player already intersecting a
                // low collision shape, or on a stair's lower tread.
                state="moving"; index=result.path().size()>1?1:0;
            }
            // Like PathExecutor's same-tick successful-movement advancement,
            // consume completed waypoints without an artificial neutral frame.
            for(int advances=0;advances<8;advances++) {
            if(index>=result.path().size()) {
                if(feet().equals(goal) && (mc.thePlayer.onGround || mc.thePlayer.isOnLadder() || ForgeSnapshot.liveSwimmable(world,goal))) {finish("succeeded","goal_reached");return;}
                if(usablePartial()) {
                    if(!progress.advance(feet())) {finish("failed","no_route_progress");return;}
                    replans++;recoveryReplans=0;recalculate();return;
                }
                finish("failed","path_ended_outside_goal");return;
            }
            BlockPos target=result.path().get(index);
            BlockPos previous=result.path().get(Math.max(0,index-1));
            TerrainGrid live=ForgeSnapshot.local(world,previous,target);
            if(index>0 && result.moves().get(index-1).startsWith("WORK_")) {
                WorkWorld.Work work=workWorld.workForEdge(previous,target);
                if(work==null) {finish("failed","work_plan_invalid");return;}
                for(BlockPos block:work.breakBlocks()) if(!world.isAirBlock(block.x(),block.y(),block.z())) {
                    lease.setKeys(Set.of());
                    workJob=new MiningJob(mc,block,Math.min(remaining,6000),true,true,lease,overrideProtection,construction!=null);state="excavating";return;
                }
                BlockPos floor=work.placeBlock();
                if(floor!=null && (world.isAirBlock(floor.x(),floor.y(),floor.z())||construction!=null&&ForgeFluids.fluid(world.getBlock(floor.x(),floor.y(),floor.z())))) {
                    lease.setKeys(Set.of());
                    if(construction==null)workJob=new PlacingJob(floor,Math.min(remaining,6000),lease);
                    else {var cell=construction.support(floor);if(cell==null){finish("failed","construction_material_changed");return;}workJob=construction.placement(cell,previous.x()==target.x()&&previous.z()==target.z());}
                    state="placing";return;
                }
            }
            if(!live.traversable(target.x(),target.y(),target.z())) {
                if(recoveryReplans++<4 && (mc.thePlayer.onGround||mc.thePlayer.isInWater()||mc.thePlayer.isOnLadder())) {replans++;recalculate();return;}
                finish("failed","path_changed");return;
            }
            double dx=target.x()+.5-mc.thePlayer.posX,dz=target.z()+.5-mc.thePlayer.posZ;
            double targetY=live.feetY(target);
            double horizontal=Math.hypot(dx,dz), vertical=targetY-mc.thePlayer.boundingBox.minY;
            boolean waterTarget=ForgeSnapshot.water(world,target);
            boolean inWater=mc.thePlayer.isInWater();
            boolean ladderTarget=live.climbable(target);
            String rawKind=index==0?"WALK":result.moves().get(index-1);
            String kind=rawKind.startsWith("WORK_")?rawKind.substring(5):rawKind;
            boolean rolling=false;
            if(index>0 && index+1<result.path().size() && kind.equals("WALK") && result.moves().get(index).equals("WALK")) {
                BlockPos next=result.path().get(index+1);
                rolling=target.x()-previous.x()==next.x()-target.x() && target.z()-previous.z()==next.z()-target.z()
                    && Math.abs(pathFeetY.get(index-1)-targetY)<.001 && Math.abs(pathFeetY.get(index+1)-targetY)<.001;
                if(!rolling && Math.abs(pathFeetY.get(index-1)-targetY)<.001 && Math.abs(pathFeetY.get(index+1)-targetY)<.001) {
                    BlockPos corner=new BlockPos(previous.x()+next.x()-target.x(),target.y(),previous.z()+next.z()-target.z());
                    rolling=ForgeSnapshot.liveStandable(world,previous)&&ForgeSnapshot.liveStandable(world,target)&&ForgeSnapshot.liveStandable(world,next)&&ForgeSnapshot.liveStandable(world,corner);
                }
            }
            if(index>0 && kind.startsWith("CLIMB") && live.moves(previous).stream().noneMatch(move->move.destination().equals(target) && move.kind().equals(kind))) {
                if(recoveryReplans++<4 && (mc.thePlayer.onGround||mc.thePlayer.isOnLadder())) {replans++;recalculate();return;}
                finish("failed","ladder_changed");return;
            }
            boolean reachedWater=waterTarget && feet().equals(target) && (inWater||mc.thePlayer.onGround);
            boolean reachedLadder=ladderTarget && mc.thePlayer.isOnLadder() && (kind.equals("CLIMB_UP")?vertical<.03:Math.abs(vertical)<.18);
            boolean finalWater=waterTarget && index==result.path().size()-1;
            boolean calm=Math.hypot(mc.thePlayer.motionX,mc.thePlayer.motionZ)<.045;
            settled=finalWater && reachedWater && horizontal<.25 && calm?settled+1:0;
            if(horizontal<(waterTarget?.3:ladderTarget?.22:rolling?.35:.11) && (reachedWater||reachedLadder||Math.abs(vertical)<.1 && mc.thePlayer.onGround && (rolling||calm)) && (!finalWater||settled>=4)) {
                index++; stalled=0; bestDistance=Double.POSITIVE_INFINITY;if(!rolling) stabilize();continue;
            }
            double distance=horizontal+(waterTarget?0:Math.abs(vertical));
            if(distance<bestDistance-.035) {bestDistance=distance;stalled=0;} else stalled++;
            if(stalled>(inWater?120:60)) {
                if(recoveryReplans++<3 && (mc.thePlayer.onGround||inWater||mc.thePlayer.isOnLadder())) {replans++;recalculate();return;}
                finish("failed","stuck");return;
            }
            // Revalidate the actual geometry, including risers and fractional ceilings.
            boolean groundKind=Set.of("WALK","STEP_UP","STEP_DOWN","ASCEND","DESCEND").contains(kind);
            if(groundKind && index>0 && live.groundMove(previous,target)==null) {finish("failed","corridor_changed");return;}
            if (!ForgeSnapshot.liveClear(world,target.x()+.5,targetY,target.z()+.5,targetY+1.8)) {
                finish("failed","collision_changed");return;
            }
            if (kind.equals("ASCEND") && mc.thePlayer.onGround
                    && !ForgeSnapshot.liveClear(world,mc.thePlayer.posX,mc.thePlayer.boundingBox.minY,mc.thePlayer.posZ,targetY+1.8)) {
                finish("failed","jump_clearance_changed");return;
            }
            if(kind.startsWith("CLIMB") || ladderTarget) {
                Set<Integer> keys=new LinkedHashSet<>();
                LadderFacing facing=live.ladder(previous);
                if(facing==LadderFacing.NONE) facing=live.ladder(target);
                if((kind.equals("CLIMB_UP") || kind.equals("CLIMB_EXIT")&&vertical>.05) && mc.thePlayer.isOnLadder()) {
                    if(facing==LadderFacing.NONE) {finish("failed","ladder_changed");return;}
                    lease.look((float)Math.toDegrees(Math.atan2(-facing.dx,facing.dz)),0);
                    keys.add(mc.gameSettings.keyBindForward.getKeyCode());
                } else if(horizontal>(kind.equals("CLIMB_ENTER")?.035:.10)) {
                    lease.look((float)Math.toDegrees(Math.atan2(-dx,dz)),0);
                    keys.add(mc.gameSettings.keyBindForward.getKeyCode());
                }
                lease.setKeys(keys);return;
            }
            // Counter observed drift, including mixed currents. Never write player velocity or position.
            double aimX=dx,aimZ=dz;
            if(inWater) {aimX-=mc.thePlayer.motionX*3;aimZ-=mc.thePlayer.motionZ*3;}
            else if(mc.thePlayer.onGround && !rolling) {aimX-=mc.thePlayer.motionX*2.2;aimZ-=mc.thePlayer.motionZ*2.2;}
            double projectedX=mc.thePlayer.posX+mc.thePlayer.motionX*3;
            double projectedZ=mc.thePlayer.posZ+mc.thePlayer.motionZ*3;
            if(!ForgeSnapshot.safeBody(world,projectedX,mc.thePlayer.boundingBox.minY,projectedZ,true)) {finish("failed","current_toward_hazard");return;}
            lease.look((float)Math.toDegrees(Math.atan2(-aimX,aimZ)),0);
            Set<Integer> keys=new LinkedHashSet<>();
            if(Math.hypot(aimX,aimZ)>.035) keys.add(mc.gameSettings.keyBindForward.getKeyCode());
            // Slow down on the arrival surface before inertia can carry the footprint onto
            // an adjacent riser. Do not sneak at a drop edge or while jumping out of water.
            if(!rolling && !inWater && !waterTarget && mc.thePlayer.onGround && Math.abs(vertical)<.1 && horizontal<.7)
                keys.add(mc.gameSettings.keyBindSneak.getKeyCode());
            if(inWater || waterTarget || (vertical>.2 && mc.thePlayer.onGround && (kind.equals("ASCEND")||kind.equals("SWIM_EXIT")))) keys.add(mc.gameSettings.keyBindJump.getKeyCode());
            lease.setKeys(keys);
            return;
            }
        }
        private void stabilize() {
            if(mc.thePlayer.isOnLadder()) {
                lease.setKeys(Set.of(mc.gameSettings.keyBindSneak.getKeyCode()));
            } else if(mc.thePlayer.isInWater()) {
                // Maintain buoyancy during capture/search; counter a current toward the cell center.
                BlockPos p=feet();
                double dx=p.x()+.5-mc.thePlayer.posX-mc.thePlayer.motionX*3;
                double dz=p.z()+.5-mc.thePlayer.posZ-mc.thePlayer.motionZ*3;
                if(Math.hypot(dx,dz)>.2 && !recoveringAir) {
                    lease.look((float)Math.toDegrees(Math.atan2(-dx,dz)),0);
                    lease.setKeys(Set.of(mc.gameSettings.keyBindJump.getKeyCode(),mc.gameSettings.keyBindForward.getKeyCode()));
                } else lease.setKeys(Set.of(mc.gameSettings.keyBindJump.getKeyCode()));
            } else lease.setKeys(Set.of());
        }
        private boolean usablePartial() {
            return result.path().size()>1 && (result.status()==SearchResult.Status.PARTIAL || result.status()==SearchResult.Status.TIMEOUT);
        }
        void finish(String terminal,String reason) {
            if(done()) return;
            state=terminal;this.reason=reason;cancelled.set(true);
            if(terminal.equals("succeeded")) arrival=Map.of("pos",List.of(mc.thePlayer.posX,mc.thePlayer.boundingBox.minY,mc.thePlayer.posZ),
                "velocity",List.of(mc.thePlayer.motionX,mc.thePlayer.motionY,mc.thePlayer.motionZ),"inWater",mc.thePlayer.isInWater(),"onLadder",mc.thePlayer.isOnLadder(),
                "controlsReleased",true,"note","arrival is an event; currents and gravity continue after input release");
            if(pending!=null) pending.cancel(false);
            if(workJob!=null && !workJob.done()) workJob.cancel(reason);
            if(lease!=null) {if(ownsLease)lease.close();else if(lease.isActive())lease.setKeys(Set.of());}
            pending=null; capture=null;planned=null;workWorld=null;world=null; player=null;
        }
        @Override public void cancel(String reason) {finish("cancelled",reason);}
        @Override public boolean done() {return state.equals("succeeded")||state.equals("failed")||state.equals("cancelled");}
        @Override public boolean succeeded() {return state.equals("succeeded");}
        @Override public Map<String,Object> status() {
            Map<String,Object> out=new LinkedHashMap<>();
            out.put("available",true);out.put("state",state);out.put("reason",reason);out.put("goal",List.of(goal.x(),goal.y(),goal.z()));
            out.put("goalCount",goals.size());
            out.put("ticks",age);out.put("calculations",calculations);out.put("replans",replans);out.put("pathIndex",index);
            out.put("captureTicks",captureTicks);out.put("capturedCells",capturedCells);out.put("wideCapture",wideCapture);
            out.put("segmentsCompleted",progress.segments());out.put("recoveryReplans",recoveryReplans);
            out.put("overrideProtection",overrideProtection);out.put("allowBreak",allowBreak);out.put("allowPlace",allowPlace);out.put("blocksMined",mined);out.put("blocksPlaced",placed);out.put("completedWork",List.copyOf(completedWork));
            if(workJob!=null) out.put("work",workJob.status());
            out.put("controlOwned",lease!=null&&lease.isActive());
            out.put("movement",MOVEMENT);out.put("recoveringAir",recoveringAir);out.put("mining",true);
            if(arrival!=null) out.put("arrival",arrival);
            if(result!=null) {
                out.put("searchStatus",result.status().name());out.put("nodes",result.nodes());out.put("cost",Double.isFinite(result.cost())?result.cost():null);
                out.put("path",result.path().stream().limit(256).map(p->List.of(p.x(),p.y(),p.z())).toList());
                out.put("pathFeetY",pathFeetY.stream().limit(256).toList());
                out.put("moves",result.moves().stream().limit(255).toList());
            }
            return out;
        }
    }
}
