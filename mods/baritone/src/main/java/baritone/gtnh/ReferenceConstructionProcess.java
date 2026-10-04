// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh;

import baritone.compat.Registry;
import baritone.Baritone;
import baritone.api.Settings;
import baritone.api.schematic.ISchematic;
import baritone.compat.IBlockState;
import baritone.compat.StackIdentity;
import baritone.compat.BlockPos;
import baritone.gtnh.pathing.*;
import static baritone.gtnh.pathing.WorkSpec.*;
import java.util.*;

/** The construction engine: native cell identity, preflight and durable intent around upstream BuilderProcess scheduling. */
final class ReferenceConstructionProcess extends BulkJob {
    private final Baritone engine;
    private final ConstructionPlan plan;
    private final Map<Settings.Setting<?>,Object> savedSettings=new LinkedHashMap<>();
    private Map<BlockPos,Cell> desired=Map.of();
    private Map<BlockPos,Boolean> correct=Map.of();
    /** Clicks the game took into a cell that has not yet been seen to match: this session's only, gone when it does. */
    private final Map<BlockPos,Integer> attempts=new HashMap<>();
    /** The cell a stop is about, when one is to blame. */
    private BlockPos blamed;
    /** This tick: wrong cells of the steps so far that can still be worked, and the first that cannot (a block the job may not remove). */
    private int open;
    private BlockPos occupied;
    private final Set<BlockPos> pending=new HashSet<>();
    private final Set<BlockPos> placedObserved=new HashSet<>(),removedObserved=new HashSet<>();
    private final Map<BlockPos,IBlockState.StateKey> previousObserved=new HashMap<>();
    private int repeat,layer,passStarts;
    private boolean started;
    private List<List<Integer>> incorrect=List.of();
    private final Set<String> movements=new LinkedHashSet<>();
    private Map<String,Object> inspection=Map.of();
    private Map<BlockPos,IBlockState> schematicStates=Map.of();
    private Map<BlockPos,Map<String,Object>> materialSelectors=Map.of();
    private Map<BlockPos,Cell> passCells=Map.of();
    private final Map<Cell,baritone.api.pathing.goals.Goal> placementGoals=new HashMap<>();
    private int placementGoalEpoch=-1;
    private BlockPos placementGoalFeet;
    /** Cells last found with nowhere to stand from which a face to place them against can be seen. */
    private final Set<BlockPos> noVantage=new LinkedHashSet<>();
    private Set<BlockPos> deferredAir=Set.of();
    private boolean cleanupPhase;
    private boolean clearanceEgress;
    private baritone.api.pathing.goals.Goal egressGoal;
    private final Map<BlockPos,baritone.api.pathing.goals.Goal> cleanupGoals=new HashMap<>();
    private long searchesBefore=Long.MAX_VALUE;
    /** The current build step (BuildSteps), the one the running pass was started at, and this tick's wrong cells per step. */
    private int buildStep,passStep=-1;
    private int[] stepLeft=new int[0];
    private BlockPos[] stepFirst=new BlockPos[0];
    ReferenceConstructionProcess(BaritoneNavigation navigation,WorkJournal journal,Map<String,Object> options){
        super(navigation,journal,options);engine=navigation.reference();
        plan=new ConstructionPlan(params,journal.progress,world);repeat=plan.repeat;layer=plan.layer;
    }
    @Override void begin(){
        super.begin();
        // A block the job may not remove can never become the plan's: say so now, with the cell, before any input.
        if(!plan.replace()){var held=held();if(!held.isEmpty()){blamed=held.get(0);finish("failed","occupied");return;}}
        if(plan.strict){
            // Blueprint preflight: refuse before any input rather than discover a conflict mid-build.
            inspection=ConstructionPlan.inspect(plan.cells,plan.replace(),override,plan::correct);
            for(String key:List.of("unloaded","conflicts","protected","unsupported","missingItems"))if(((Number)inspection.get(key)).intValue()>0){finish("failed","preflight_"+key);return;}
        }
        engine.getPathingBehavior().forceCancel();engine.snags.reset();searchesBefore=engine.getPathingBehavior().calculationsStarted();
        for(var setting:Baritone.settings().allSettings)savedSettings.put(setting,setting.value);
        initializeClearance();configure();engine.overrideProtection=override;engine.positionAllowed=p->true;
        engine.getInputOverrideHandler().attach(lease);
        capture();startPass();
    }
    /** Plan cells that want a block and hold another one, which a placement would not replace. */
    private List<BlockPos> held(){return plan.cells.stream().filter(c->!c.clear()&&plan.occupied(c.pos())&&!plan.correct(c)).map(Cell::pos).toList();}
    private void initializeClearance(){
        if(integer(journal.progress,"clearanceRepeat",-1,-1,100000)==repeat){
            Set<BlockPos> restored=new HashSet<>();for(Object p:list(journal.progress.getOrDefault("deferredAir",List.of())))restored.add(pos(p));
            Set<BlockPos> authorized=new HashSet<>();plan.cells.stream().filter(Cell::clear).forEach(c->authorized.add(c.pos()));
            if(!authorized.containsAll(restored))throw new IllegalArgumentException("saved clearance outside explicit air cells");
            deferredAir=Set.copyOf(restored);cleanupPhase=bool(journal.progress,"cleanupPhase",false);
            clearanceEgress=bool(journal.progress,"clearanceEgress",false);
        }else{
            deferredAir=DeferredClearance.capture(plan.cells,allowPlace,p->plan.loaded(p)&&world.isAirBlock(p.getX(),p.getY(),p.getZ()));
            cleanupPhase=false;clearanceEgress=false;egressGoal=null;journal.progress.put("clearanceRepeat",repeat);
            journal.progress.put("deferredAir",deferredAir.stream().map(p->List.of(p.getX(),p.getY(),p.getZ())).toList());
            journal.progress.put("cleanupPhase",false);
            journal.progress.put("clearanceEgress",false);
        }
        journal.save(status());
    }
    private void configure(){
        var settings=Baritone.settings();
        settings.allowBreak.value=allowBreak;settings.allowPlace.value=allowPlace&&(!cleanupPhase||clearanceEgress);
        settings.allowInventory.value=plan.settings.bool("allowInventory",true);
        // The canonical plan has already frozen selection, orientation, substitutions and map-art filtering.
        settings.schematicOrientationX.value=false;settings.schematicOrientationY.value=false;settings.schematicOrientationZ.value=false;
        settings.buildSubstitutes.value=Map.of();settings.mapArtMode.value=false;settings.buildOnlySelection.value=false;
        settings.buildRepeat.value=new baritone.compat.Vec3i(0,0,0);settings.buildRepeatCount.value=1;
        for(String key:List.of("buildInLayers","layerOrder","skipFailedLayers","breakFromAbove","goalBreakFromAbove","distanceTrim","buildIgnoreExisting","okIfWater")){
            var setting=settings.byLowerName.get(key.toLowerCase(Locale.ROOT));
            ReferenceSettings.copy(setting,plan.settings.bool(key,(Boolean)setting.defaultValue));
        }
        for(String key:List.of("layerHeight","incorrectSize","builderTickScanRadius")){
            var setting=settings.byLowerName.get(key.toLowerCase(Locale.ROOT));ReferenceSettings.copy(setting,plan.settings.integer(key,(Integer)setting.defaultValue));
        }
        settings.startAtLayer.value=layer;settings.breakCorrectBlockPenaltyMultiplier.value=plan.settings.number("breakCorrectBlockPenaltyMultiplier",10);
        // BlockPlaceHelper waits rightClickSpeed ticks after a click, so the interval is one more than the setting.
        if(plan.settings.values.containsKey("clickInterval"))settings.rightClickSpeed.value=plan.settings.integer("clickInterval",5)-1;
        if(cleanupPhase){settings.buildInLayers.value=false;settings.startAtLayer.value=0;}
        settings.buildIgnoreDirection.value=false;settings.buildIgnoreProperties.value=List.of();
        settings.buildIgnoreBlocks.value=blocks(plan.settings.ids("buildIgnoreBlocks"));
        settings.buildSkipBlocks.value=blocks(plan.settings.ids("buildSkipBlocks"));settings.okIfAir.value=blocks(plan.settings.ids("okIfAir"));
        Map<net.minecraft.block.Block,List<net.minecraft.block.Block>> substitutes=new HashMap<>();
        for(var e:plan.settings.mappings("buildValidSubstitutes").entrySet())substitutes.put(blocks(List.of(e.getKey())).get(0),blocks(list(e.getValue()).stream().map(Object::toString).toList()));
        settings.buildValidSubstitutes.value=substitutes;
        if(!plan.throwaways.isEmpty()){
            List<net.minecraft.item.Item> items=new ArrayList<>();
            for(var stack:mc.thePlayer.inventory.mainInventory)if(stack!=null&&plan.throwaways.stream().anyMatch(selector->WorkAccess.item(stack,selector)))items.add(stack.getItem());
            settings.acceptableThrowawayItems.value=items;
        }
        engine.getInventoryBehavior().throwawayFilter=stack->plan.throwaways.isEmpty()||plan.throwaways.stream().anyMatch(selector->WorkAccess.item(stack,selector));
    }
    private static List<net.minecraft.block.Block> blocks(List<String> ids){
        return ids.stream().map(Registry::block).toList();
    }
    private void capture(){
        BlockPos currentFeet=WorkAccess.feet();
        if(placementGoalEpoch!=ticks/20||!currentFeet.equals(placementGoalFeet)){
            placementGoalEpoch=ticks/20;placementGoalFeet=currentFeet;placementGoals.clear();cleanupGoals.clear();
        }
        Map<BlockPos,Cell> cells=new HashMap<>();Map<BlockPos,Boolean> matches=new HashMap<>();
        Map<BlockPos,Set<StackIdentity>> materials=new HashMap<>();Map<BlockPos,Map<String,Object>> selectors=new HashMap<>();
        Map<Map<String,Object>,Set<StackIdentity>> matchingInventory=new HashMap<>();
        Map<BlockPos,IBlockState> states=new HashMap<>();Map<net.minecraft.block.Block,Integer> masks=new HashMap<>();
        // Wrong cells per build step are counted in this same walk over the plan: the order costs no pass of its own.
        var order=plan.steps;int[] left=new int[order.count()];BlockPos[] first=new BlockPos[left.length];int walked=0;
        boolean replace=plan.replace();open=0;occupied=null;Set<BlockPos> soft=new HashSet<>();
        for(Cell source:plan.cells){
            Cell cell=plan.desired(source);BlockPos p=cell.pos();cells.put(p,cell);int at=order.index(walked++);boolean done=false,stuck=false;
            var block=cell.clear()?net.minecraft.init.Blocks.air:ConstructionPlan.block(cell);
            states.put(p,new IBlockState(block,cell.meta(),null,p.getX(),p.getY(),p.getZ()));
            masks.put(block,plan.settings.metadataMask(cell.id()));
            if(!cell.clear()){
                var selector=Map.copyOf(ConstructionPlan.material(cell));selectors.put(p,selector);
                materials.put(p,matchingInventory.computeIfAbsent(selector,key->{
                    Set<StackIdentity> eligible=new HashSet<>();
                    for(var stack:mc.thePlayer.inventory.mainInventory)if(stack!=null&&WorkAccess.item(stack,key))eligible.add(StackIdentity.capture(stack));
                    return Set.copyOf(eligible);
                }));
            }
            if(plan.loaded(p)){
                boolean now=plan.correct(source);matches.put(p,now);done=now;
                IBlockState.StateKey state=new IBlockState.StateKey(world.getBlock(p.getX(),p.getY(),p.getZ()),world.getBlockMetadata(p.getX(),p.getY(),p.getZ()));
                var previous=previousObserved.put(p,state);
                // Seen to match: the clicks it took are forgotten, so a later repair of this cell starts from none.
                if(now&&pending.remove(p)){attempts.remove(p);if(cell.clear())removedObserved.add(p);else placedObserved.add(p);}
                boolean empty=baritone.compat.LegacyPlacement.empty(world,p.getX(),p.getY(),p.getZ());
                // What stands in a cell that wants a block: nothing or something a placement replaces (which the job may
                // also break, replace or not: it costs nothing that the placement would not), or a block in the way.
                if(!now&&!cell.clear()){if(!empty)stuck=!replace||pending.contains(p);else if(!state.block().isAir(world,p.getX(),p.getY(),p.getZ()))soft.add(p);}
                // Explicit-air cells may start empty, receive an autonomous
                // scaffold, then be cleared again. Count that observed removal
                // even though the final block equals its initial state.
                if(previous!=null&&!state.equals(previous)&&cell.clear()&&now)removedObserved.add(p);
            }
            if(at>=0&&!done&&left[at]++==0)first[at]=p;
            // The step is last tick's: it only moves on once everything before it is done, so nothing counted here is early.
            if(!done&&at<=buildStep){if(!stuck)open++;else if(occupied==null)occupied=p;}
        }
        stepLeft=left;stepFirst=first;buildStep=BuildSteps.current(buildStep,left);int shown=buildStep;
        desired=Map.copyOf(cells);correct=Map.copyOf(matches);schematicStates=Map.copyOf(states);materialSelectors=Map.copyOf(selectors);
        var snapshot=desired;var verified=correct;var pendingSnapshot=Set.copyOf(pending);
        var materialSnapshot=Map.copyOf(materials);var masksSnapshot=Map.copyOf(masks);
        boolean restricted=plan.restricted(),clearing=cleanupPhase;
        var softSnapshot=Set.copyOf(soft);
        engine.getBuilderProcess().stateValidator=(current,wanted,itemVerify)->{
            Cell cell=snapshot.get(new BlockPos(wanted.x,wanted.y,wanted.z));
            if(cell==null)return true;
            if(itemVerify){
                if(cell.clear())return current.getBlock()==net.minecraft.init.Blocks.air;
                var identity=current.placementIdentity();
                return identity!=null&&materialSnapshot.getOrDefault(cell.pos(),Set.of()).contains(identity);
            }
            return cell.verify().isEmpty()||verified.getOrDefault(new BlockPos(current.x,current.y,current.z),false);
        };
        // stateValidator already requires the cell's exact native item selector,
        // including metadata and NBT. A sample placement state (such as a lower
        // slab) must not make that material unavailable for another legal pose.
        // Only approximate inventory states use this; actual click prediction
        // and final world verification still require the requested block state.
        engine.getBuilderProcess().approximateMaterialMatches=(current,wanted)->current.getBlock()==wanted.getBlock();
        engine.getBuilderProcess().mayBreak=breakRule(snapshot,verified,clearing?Set.of():deferredAir,softSnapshot,pendingSnapshot,replace,allowBreak&&!restricted);
        engine.getBuilderProcess().mayPlace=p->!restricted||snapshot.containsKey(new BlockPos(p.getX(),p.getY(),p.getZ()));
        Set<BlockPos> poseSensitive=new HashSet<>();
        for(Cell cell:snapshot.values())if(!cell.clear()&&(!cell.placement().isEmpty()||baritone.compat.LegacyStateProperties.hasOrientation(ConstructionPlan.block(cell))))poseSensitive.add(cell.pos());
        var sensitive=Set.copyOf(poseSensitive);
        // Movement placement has no final-facing state contract. Let the source
        // builder place these from a verified pose before treating them as terrain.
        // Nor may a walk bridge or pillar with anything, plan block or throwaway, into a cell whose step has not come.
        engine.getBuilderProcess().movementMayPlace=p->{BlockPos pos=new BlockPos(p.getX(),p.getY(),p.getZ());return !sensitive.contains(pos)&&order.visible(pos,shown);};
        engine.getBuilderProcess().stateComparison=(actual,wanted)->{
            if(actual.getBlock()!=wanted.getBlock())return false;
            int mask=masksSnapshot.getOrDefault(wanted.getBlock(),15);
            return (actual.meta&mask)==(wanted.meta&mask);
        };
        engine.getBuilderProcess().placementFace=(state,face)->{
            Cell c=snapshot.get(new BlockPos(state.x,state.y,state.z));
            return c==null||!c.placement().containsKey("face")||integer(c.placement(),"face",0,0,5)==face.ordinal();
        };
        engine.getBuilderProcess().placementPoint=(state,support,point)->{
            Cell c=snapshot.get(new BlockPos(state.x,state.y,state.z));if(c==null||!c.placement().containsKey("hit"))return point;
            var hit=list(c.placement().get("hit"));
            return new baritone.compat.Vec3d(support.getX()+((Number)hit.get(0)).doubleValue(),support.getY()+((Number)hit.get(1)).doubleValue(),support.getZ()+((Number)hit.get(2)).doubleValue());
        };
        engine.getBuilderProcess().placementRotation=(state,rotation)->{
            Cell c=snapshot.get(new BlockPos(state.x,state.y,state.z));if(c==null)return rotation;
            return new baritone.api.utils.Rotation((float)number(c.placement(),"yaw",rotation.getYaw(),-360000,360000),(float)number(c.placement(),"pitch",baritone.api.utils.Rotation.clampPitch(rotation.getPitch()),-90,90));
        };
        engine.getBuilderProcess().deferredPlacementState=state->{Cell c=snapshot.get(new BlockPos(state.x,state.y,state.z));return c!=null&&bool(c.placement(),"verifyAfterPlacement",false);};
        engine.getBuilderProcess().placementGoalAdapter=(target,goal)->{
            Cell cell=snapshot.get(new BlockPos(target.getX(),target.getY(),target.getZ()));
            if(cell==null||cell.clear())return goal;
            if(placementGoals.containsKey(cell))return placementGoals.get(cell);
            int slot=plan.slot(cell);if(slot<0||!mc.thePlayer.onGround)return goal;
            Set<baritone.compat.BlockPos> legal=new HashSet<>(),adjacent=new HashSet<>();
            for(var pose:WorkAccess.buildingApproaches(world,cell.pos())){
                var sourceFeet=baritone.compat.NavigationCoordinates.feet(pose.feet().getX()+.5,pose.standingY(),pose.feet().getZ()+.5,
                    p->world.getBlock(p.getX(),p.getY(),p.getZ()) instanceof net.minecraft.block.BlockSlab);
                if(!sourcePlacementHeight(cell,sourceFeet))continue;
                // PathExecutor reaches a block goal before necessarily reaching
                // its center. At the current cell use the real body position:
                // otherwise a boundary-overlapping player can be declared ready
                // to place its neighbor forever, while native collision rejects it.
                boolean here=pose.feet().equals(currentFeet);
                double y=here?mc.thePlayer.boundingBox.minY:pose.standingY();
                PlacementGoalSupport.Probe probe=(x,z)->engine.getBuilderProcess().canPlaceFrom(schematicStates.get(cell.pos()),x,y,z,slot);
                // Any other cell is promised from its centre, where the player will not be standing. A view that exists
                // only from the exact centre (a ray through the seam of two blocks meeting at an edge) is no stance:
                // on arrival the real position refutes it and the next such cell becomes the goal, back and forth.
                if(here?probe.at(mc.thePlayer.posX,mc.thePlayer.posZ):PlacementGoalSupport.steady(pose.feet().getX()+.5,pose.feet().getZ()+.5,probe)){
                    legal.add(sourceFeet);if(goal.isInGoal(sourceFeet))adjacent.add(sourceFeet);
                }
            }
            // No existing vantage is not proof that construction is impossible:
            // the source planner may still build the support it needs to stand on.
            if(legal.isEmpty()){
                // Standing in the cell itself fails native collision from every vantage, and the source goal (stand on top of the
                // new block) is unreachable without scaffolding: step out to a neighbouring column first, then this adapter runs again.
                var at=cell.pos();
                if(!mc.thePlayer.boundingBox.intersectsWith(net.minecraft.util.AxisAlignedBB.getBoundingBox(at.getX(),at.getY(),at.getZ(),at.getX()+1,at.getY()+1,at.getZ()+1))){noVantage.add(at);return goal;}
                // A low neighbouring stance can still overlap a different floor
                // cell, while searchForPlaceables refuses every upward click.
                // Egress must reach a height where this cell becomes actionable.
                boolean covered=world.getBlock(at.getX(),at.getY()+1,at.getZ())!=net.minecraft.init.Blocks.air;int up=reachUp();
                var out=WorkAccess.buildingApproaches(world,at).stream().map(pose->pose.feet()).filter(f->PlacementGoalSupport.egress(at,f,covered,up)&&ForgeSnapshot.liveStandable(world,f))
                    .map(f->(baritone.api.pathing.goals.Goal)new baritone.api.pathing.goals.GoalBlock(f)).toArray(baritone.api.pathing.goals.Goal[]::new);
                return out.length==0?goal:new baritone.api.pathing.goals.GoalComposite(out);
            }
            noVantage.remove(cell.pos());
            // Retain source adjacency where native placement permits it. An
            // obstructing half/full block can instead require a higher vantage;
            // use the source GoalPlace preference and native reach for those.
            baritone.api.pathing.goals.Goal adapted=!adjacent.isEmpty()?new baritone.compat.NativePlacementGoal(goal,adjacent):
                new baritone.api.pathing.goals.GoalComposite(legal.stream().map(p->new baritone.process.BuilderProcess.GoalPlace(p.down())).toArray(baritone.api.pathing.goals.Goal[]::new));
            // Existing work poses supplement ordinary source goals. Removing a
            // future goal would prevent A* from constructing its own support
            // (notably a pillar), even though a distant existing pose is legal.
            if(!sensitive.contains(cell.pos()))adapted=!adjacent.isEmpty()?goal:new baritone.api.pathing.goals.GoalComposite(goal,adapted);
            placementGoals.put(cell,adapted);return adapted;
        };
        engine.getBuilderProcess().breakGoalAdapter=(target,goal)->{
            BlockPos p=new BlockPos(target.getX(),target.getY(),target.getZ());
            if(!cleanupPhase||!deferredAir.contains(p))return goal;
            return cleanupGoals.computeIfAbsent(p,key->{
                List<baritone.api.pathing.goals.Goal> goals=new ArrayList<>();goals.add(goal);
                for(var pose:WorkAccess.buildingApproaches(world,p)){
                    var feet=pose.feet();int dy=p.getY()-feet.getY();
                    // Match source toBreakNearPlayer's actionable height range.
                    if(dy<0||dy>5||feet.equals(p)||!ForgeSnapshot.liveStandable(world,feet))continue;
                    var eye=feet.equals(currentFeet)?mc.thePlayer.getPosition(1):WorkAccess.eyeAt(pose);
                    if(MiningJob.reachable(mc,world,p,eye)!=null)
                        goals.add(new baritone.api.pathing.goals.GoalBlock(feet));
                }
                return new baritone.api.pathing.goals.GoalComposite(goals.toArray(baritone.api.pathing.goals.Goal[]::new));
            });
        };
        if(clearanceEgress&&!DeferredClearance.needsEgress(deferredAir,p->correct.getOrDefault(p,false))){
            // A paused job may be resumed after another actor cleared its supports.
            clearanceEgress=false;journal.progress.put("clearanceEgress",false);configure();
        }
        if(clearanceEgress&&egressGoal==null){
            Set<baritone.compat.BlockPos> safe=new HashSet<>();
            for(BlockPos p:deferredAir)if(!correct.getOrDefault(p,false))
                for(var pose:WorkAccess.buildingApproaches(world,p)){
                    var feet=pose.feet();
                    if(feet.getY()>p.getY()||deferredAir.contains(new BlockPos(feet.getX(),feet.getY()-1,feet.getZ()))||!ForgeSnapshot.liveStandable(world,feet))continue;
                    if(MiningJob.reachable(mc,world,p,WorkAccess.eyeAt(pose))!=null)safe.add(feet);
                }
            if(safe.isEmpty())throw new IllegalStateException("no_observed_clearance_egress_pose");
            egressGoal=new baritone.api.pathing.goals.GoalComposite(safe.stream().map(baritone.api.pathing.goals.GoalBlock::new).toArray(baritone.api.pathing.goals.Goal[]::new));
        }
        engine.getBuilderProcess().accessGoal=()->clearanceEgress?egressGoal:null;
        // Immutable explicit permissions match exact observed states inside the plan only.
        Map<BlockPos,IBlockState.StateKey> breaks=new HashMap<>();
        for(Cell cell:desired.values())if((replace||cell.clear()||soft.contains(cell.pos()))&&!correct.getOrDefault(cell.pos(),false)&&plan.loaded(cell.pos())&&!pending.contains(cell.pos())){
            BlockPos p=cell.pos();breaks.put(p,new IBlockState.StateKey(world.getBlock(p.getX(),p.getY(),p.getZ()),world.getBlockMetadata(p.getX(),p.getY(),p.getZ())));
        }
        Map<BlockPos,IBlockState.StateKey> breakSnapshot=Map.copyOf(breaks);
        engine.explicitMiningTargets=()->state->state.key().equals(breakSnapshot.get(new BlockPos(state.x,state.y,state.z)));
        engine.getBuilderProcess().beforePlace=p->{
            BlockPos pos=new BlockPos(p.getX(),p.getY(),p.getZ());Cell cell=desired.get(pos);
            if(cell==null||cell.clear()){
                if(cell==null&&plan.restricted())throw new IllegalStateException("placement outside restricted schematic");
                // Source BuilderCalculationContext deliberately permits temporary
                // supports where the final schematic wants air. Validate the
                // selected throwaway, not the finished cell's material selector.
                // Explicit-air verification still requires their removal.
                var held=mc.thePlayer.getHeldItem();
                if(cleanupPhase&&!clearanceEgress||!allowPlace||held==null||!(held.getItem() instanceof net.minecraft.item.ItemBlock)
                        ||!engine.getInventoryBehavior().isGenericThrowaway(held))
                    throw new IllegalStateException("temporary support material changed before placement");
                return;
            }
            if(!WorkAccess.item(mc.thePlayer.getHeldItem(),materialSelectors.get(pos)))throw new IllegalStateException("schematic material changed before placement");
            var hit=baritone.compat.RayTraceResult.fromNative(mc.objectMouseOver);
            if(hit==null||hit.typeOfHit!=baritone.compat.RayTraceResult.Type.BLOCK)throw new IllegalStateException("schematic placement ray disappeared");
            var wanted=schematicStates.get(pos);
            var predicted=baritone.compat.LegacyPlacement.predict(engine.getPlayerContext(),mc.thePlayer.getHeldItem(),hit,engine.getPlayerContext().playerRotations());
            if(!engine.getBuilderProcess().placementFace.test(wanted,hit.sideHit)||!bool(cell.placement(),"verifyAfterPlacement",false)&&!engine.getBuilderProcess().stateComparison.test(predicted,wanted)){
                finish("paused","native_placement_prediction_changed");return;
            }
            if(attempts.getOrDefault(pos,0)>=plan.attemptLimit()){blamed=pos;finish("paused","attempt_limit");}
        };
        // Charged only for a click the game took. One it refused placed nothing, and is not the cell's fault.
        engine.getPlayerContext().playerController().placed=p->{
            BlockPos pos=new BlockPos(p.getX(),p.getY(),p.getZ());Cell cell=desired.get(pos);if(cell==null||cell.clear())return;
            int count=attempts.merge(pos,1,Integer::sum);journal.recordAttempt(ConstructionPlan.key(cell),count);pending.add(pos);
            placementGoals.clear();
        };
    }
    /**
     * What the job may break, for the builder and for every path through the plan: PlanBreaks decides for plan cells
     * (`soft` ones hold only what a placement would replace, so they need no replace), `outside` for everything else.
     * All arguments are snapshots: path searches ask from their own threads.
     */
    static java.util.function.Predicate<BlockPos> breakRule(Map<BlockPos,Cell> cells,Map<BlockPos,Boolean> verified,Set<BlockPos> deferred,Set<BlockPos> soft,Set<BlockPos> pending,boolean replace,boolean outside){
        return p->{
            BlockPos pos=new BlockPos(p.getX(),p.getY(),p.getZ());Cell cell=cells.get(pos);
            if(cell==null)return outside;
            return PlanBreaks.allowed(cell.clear(),verified.getOrDefault(pos,false),deferred.contains(pos),replace||soft.contains(pos),pending.contains(pos));
        };
    }
    private boolean sourcePlacementHeight(Cell cell,baritone.compat.BlockPos sourceFeet){
        // A goal must be actionable by searchForPlaceables, not merely within
        // native click reach. Unsupported vertical construction remains the
        // responsibility of MovementPillar; a ceiling can supply support.
        return PlacementGoalSupport.actionableHeight(cell.pos().getY(),sourceFeet.getY(),world.getBlock(cell.pos().getX(),cell.pos().getY()+1,cell.pos().getZ())!=net.minecraft.init.Blocks.air,reachUp());
    }
    /** The source scan's upward limit, from this player's sneaking eye and native reach. */
    private int reachUp(){
        return PlacementGoalSupport.reachUp(baritone.compat.LegacyPlayer.sneakingEyes(mc.thePlayer).y-mc.thePlayer.boundingBox.minY,mc.playerController.getBlockReachDistance());
    }
    private boolean centerForPlacement(){
        if(!mc.thePlayer.onGround||!pending.isEmpty())return false;
        var feet=engine.getPlayerContext().playerFeet();
        double y=mc.thePlayer.boundingBox.minY,x=feet.getX()+.5,z=feet.getZ()+.5;
        if(!ForgeSnapshot.liveStandable(world,feet)||!ForgeSnapshot.liveClear(world,x,y,z,y+1.8))return false;
        // Only the source builder's active layer may request a placement pose.
        // Future roof cells must not pull the player back from a wall traversal.
        for(var target:engine.getBuilderProcess().incorrectPositions()){
            Cell cell=desired.get(new BlockPos(target.getX(),target.getY(),target.getZ()));if(cell==null)continue;
            if(cell.clear()||correct.getOrDefault(cell.pos(),false)||!sourcePlacementHeight(cell,feet))continue;
            var p=cell.pos();
            if(!mc.thePlayer.boundingBox.intersectsWith(net.minecraft.util.AxisAlignedBB.getBoundingBox(p.getX(),p.getY(),p.getZ(),p.getX()+1,p.getY()+1,p.getZ()+1)))continue;
            int slot=plan.slot(cell);if(slot<0)continue;
            var wanted=schematicStates.get(p);
            // Block goals can finish at a boundary, where the player's body
            // still intersects a neighbouring placement. Ask the native click
            // predictor about both poses before adjusting within this footing.
            if(engine.getBuilderProcess().canPlaceFrom(wanted,mc.thePlayer.posX,y,mc.thePlayer.posZ,slot)
                ||!engine.getBuilderProcess().canPlaceFrom(wanted,x,y,z,slot))continue;
            engine.getPathingBehavior().cancelSegmentIfSafe();
            engine.getInputOverrideHandler().clearAllKeys();engine.getInputOverrideHandler().flush();
            double dx=x-mc.thePlayer.posX,dz=z-mc.thePlayer.posZ;
            lease.look((float)Math.toDegrees(Math.atan2(-dx,dz)),mc.thePlayer.rotationPitch);
            lease.setKeys(Set.of(mc.gameSettings.keyBindSneak.getKeyCode(),mc.gameSettings.keyBindForward.getKeyCode()));
            placementGoals.clear();inspection=Map.of("centeringForPlacement",List.of(p.getX(),p.getY(),p.getZ()),"footing",List.of(feet.x,feet.y,feet.z));
            state="positioning";return true;
        }
        return false;
    }
    private void startPass(){
        passStarts++;
        if(plan.cells.isEmpty()){finish("succeeded","empty_selected_schematic");return;}
        int minX=plan.cells.stream().mapToInt(c->c.pos().getX()).min().orElseThrow(),minY=Math.min(plan.minY,plan.cells.stream().mapToInt(c->c.pos().getY()).min().orElseThrow()),minZ=plan.cells.stream().mapToInt(c->c.pos().getZ()).min().orElseThrow();
        // Canonical cells may have offsets outside the size used for repeat
        // orientation. The source schematic must include every selected cell.
        int width=plan.cells.stream().mapToInt(c->c.pos().getX()).max().orElseThrow()-minX+1,height=Math.max(plan.maxY,plan.cells.stream().mapToInt(c->c.pos().getY()).max().orElseThrow())-minY+1,length=plan.cells.stream().mapToInt(c->c.pos().getZ()).max().orElseThrow()-minZ+1;
        // The source builder is shown the plan up to the current step only; step() starts a new pass when the step moves on.
        Map<BlockPos,IBlockState> frozen=plan.steps.schematic(DeferredClearance.schematic(schematicStates,deferredAir,cleanupPhase),buildStep);
        passCells=desired;passStep=buildStep;
        ISchematic schematic=new ISchematic(){
            public int widthX(){return width;}public int heightY(){return height;}public int lengthZ(){return length;}
            public boolean inSchematic(int x,int y,int z,IBlockState current){return frozen.containsKey(new BlockPos(x+minX,y+minY,z+minZ));}
            public IBlockState desiredState(int x,int y,int z,IBlockState current,List<IBlockState> materials){
                return frozen.getOrDefault(new BlockPos(x+minX,y+minY,z+minZ),current);
            }
        };
        engine.bsi=new baritone.utils.BlockStateInterface(engine.getPlayerContext());
        engine.getBuilderProcess().build(String.valueOf(params.getOrDefault("name","ModdedBench schematic")),schematic,new baritone.compat.Vec3i(minX,minY,minZ));
        engine.getBuilderProcess().restoreProgress(layer,0);started=true;
    }
    @Override int progress(){return placedObserved.size()+removedObserved.size();}
    // A cell nothing can be placed against, or one the player cannot leave, keeps the source builder at its goal or
    // replanning for ever; the shared watchdog ends that. A pending placement or a block cleared is work too.
    @Override long activity(){return java.util.Objects.hash(placedObserved.size(),removedObserved.size(),pending.size());}
    @Override String phase(){return "reference_build";}
    @Override void step(){
        if(!started)return;
        capture();var builder=engine.getBuilderProcess();layer=builder.layer();
        if(clearanceEgress&&mc.thePlayer.onGround&&egressGoal.isInGoal(engine.getPlayerContext().playerFeet())){
            clearanceEgress=false;journal.progress.put("clearanceEgress",false);
            engine.getPathingBehavior().forceCancel();configure();capture();startPass();journal.save(status());return;
        }
        // Nothing left that can be placed or cleared, and a block the job may not remove stands in a cell: no walk ends this.
        if(open==0&&occupied!=null){blamed=occupied;finish(session()>0?"paused":"failed","occupied");return;}
        if(passStep!=buildStep||builder.isActive()&&!passCells.equals(desired)){
            // The build step moved on (the source builder may already have stopped, its shown cells all done), or a
            // material substitution changed. Existing searches retain their immutable schematic; start a new source
            // plan with the same progress.
            engine.getPathingBehavior().forceCancel();startPass();
        }
        journal.progress.put("layer",layer);journal.progress.put("repeat",repeat);
        if(!builder.isActive()){
            if(!cleanupPhase&&!deferredAir.isEmpty()){
                if(plan.cells.stream().filter(c->!deferredAir.contains(c.pos())).anyMatch(c->!plan.correct(c))){finish("paused","source_stopped_with_unverified_cells");return;}
                clearanceEgress=DeferredClearance.needsEgress(deferredAir,p->correct.getOrDefault(p,false));
                cleanupPhase=true;layer=0;journal.progress.put("cleanupPhase",true);journal.progress.put("clearanceEgress",clearanceEgress);
                engine.getPathingBehavior().forceCancel();configure();capture();startPass();journal.save(status());return;
            }
            List<Cell> missing=plan.cells.stream().filter(c->!plan.correct(c)).toList();
            if(!missing.isEmpty()){finish("paused","source_stopped_with_unverified_cells");return;}
            int max=plan.settings.integer("buildRepeatCount",1);
            if(!plan.settings.repeat().equals(new BlockPos(0,0,0))&&(max==-1||repeat+1<max)){
                repeat++;layer=plan.settings.integer("startAtLayer",0);plan.repeat=repeat;plan.layer=layer;plan.installSchematic();buildStep=0;
                initializeClearance();capture();configure();startPass();journal.save(status());return;
            }
            finish("succeeded","schematic_verified");return;
        }
        if(builder.isPaused()){
            var missing=desired.values().stream().filter(c->!correct.getOrDefault(c.pos(),false)).toList();
            inspection=ConstructionPlan.inspect(missing,true,override);
            // The source builder stopped for want of something for the cells it is shown: those of the steps so far.
            boolean unavailable=missing.stream().filter(c->!c.clear()&&!plan.occupied(c.pos())&&plan.steps.visible(c.pos(),buildStep)).allMatch(c->plan.slot(c)<0);
            finish("paused",!pending.isEmpty()?"placement_not_verified_inspect_before_retry":unavailable?"missing_materials":"source_builder_requires_materials_or_access");return;
        }
        if(centerForPlacement())return;
        engine.tickStart();
        // Placement callbacks may finish and release this job during the source tick.
        if(done())return;
        incorrect=builder.incorrectPositions().stream().limit(128).map(p->List.of(p.x,p.y,p.z)).toList();
        var interaction=new LinkedHashMap<String,Object>(builder.placementDiagnostic);
        interaction.put("requestedInputs",java.util.Arrays.stream(baritone.api.utils.input.Input.values()).filter(engine.getInputOverrideHandler()::isInputForcedDown).map(Enum::name).toList());
        interaction.put("actualRotation",List.of(mc.thePlayer.rotationYaw,mc.thePlayer.rotationPitch));
        if(!builder.placementDiagnostic.isEmpty()){
            var eye=mc.thePlayer.getPosition(1);var look=mc.thePlayer.getLook(1);
            var sourceLook=baritone.api.utils.RotationUtils.calcLookDirectionFromRotation(engine.getPlayerContext().playerRotations());
            interaction.put("nativeEye",List.of(eye.xCoord,eye.yCoord,eye.zCoord));interaction.put("nativeLook",List.of(look.xCoord,look.yCoord,look.zCoord));
            interaction.put("sourceLook",List.of(sourceLook.x,sourceLook.y,sourceLook.z));interaction.put("sneaking",mc.thePlayer.isSneaking());
            interaction.put("nativeReach",mc.playerController.getBlockReachDistance());
        }
        var sourceGoal=engine.getPathingBehavior().getGoal();
        var feet=engine.getPlayerContext().playerFeet();var pathStart=engine.getPathingBehavior().pathStart();
        String goalDescription=String.valueOf(sourceGoal);
        interaction.put("goal",goalDescription.substring(0,Math.min(8192,goalDescription.length())));
        interaction.put("goalTruncated",goalDescription.length()>8192);
        interaction.put("feet",List.of(feet.x,feet.y,feet.z));interaction.put("pathStart",List.of(pathStart.x,pathStart.y,pathStart.z));
        interaction.put("feetInGoal",sourceGoal!=null&&sourceGoal.isInGoal(feet));interaction.put("pathStartInGoal",sourceGoal!=null&&sourceGoal.isInGoal(pathStart));
        interaction.put("controllingProcess",engine.getPathingControlManager().mostRecentInControl().map(p->p.getClass().getSimpleName()).orElse("none"));
        interaction.put("command",engine.getPathingControlManager().mostRecentCommand().map(c->c.commandType.name()).orElse("none"));
        interaction.put("pathing",engine.getPathingBehavior().isPathing());interaction.put("calculating",engine.getPathingBehavior().getInProgress().isPresent());
        interaction.put("passStarts",passStarts);interaction.put("lastCancellation",engine.getPathingBehavior().lastCancellation());
        interaction.put("lastCalculation",engine.getPathingBehavior().lastCalculation());
        var mouse=mc.objectMouseOver;
        if(mouse!=null)interaction.put("nativeHit",Map.of("type",mouse.typeOfHit.name(),"pos",List.of(mouse.blockX,mouse.blockY,mouse.blockZ),"side",mouse.sideHit));
        inspection=Map.of("interaction",interaction);
        var path=engine.getPathingBehavior().getCurrent();if(path!=null)path.getPath().movements().forEach(m->movements.add(m.getClass().getSimpleName()));
        state="building";
        if(ticks%20==0)journal.save(status());
    }
    @Override void releaseProcess(){
        journal.progress.put("layer",layer);journal.progress.put("repeat",repeat);
        engine.getPathingBehavior().forceCancel();engine.getInputOverrideHandler().release();engine.getBuilderProcess().resetAdapters();engine.getPlayerContext().playerController().placed=p->{};engine.explicitMiningTargets=()->s->false;
        engine.overrideProtection=false;engine.positionAllowed=p->true;
        engine.getInventoryBehavior().throwawayFilter=stack->true;
        for(var entry:savedSettings.entrySet())ReferenceSettings.copy(entry.getKey(),entry.getValue());
    }
    @Override public Map<String,Object> status(){
        var out=super.status();out.put("engine","baritone-1.2.19-source-port");out.put("process","BuilderProcess");out.put("mode",plan==null?null:plan.mode());
        out.put("buildPhase",clearanceEgress?"clearance_egress":cleanupPhase?"clearance":"construction");out.put("deferredAirCells",deferredAir.size());
        out.put("placed",placedObserved.size());out.put("removed",removedObserved.size());out.put("layer",layer);out.put("repeat",repeat);out.put("incorrect",incorrect);
        out.put("selected",plan==null?0:plan.cells.size());out.put("pendingPlacementVerification",pending.size());out.put("movementTypes",List.copyOf(movements));out.put("inspection",inspection);
        // One stop, one cell: what ended the job and where.
        if(blamed!=null)out.put("stopped",Map.of("reason",reason,"pos",point(blamed)));
        if(blamed!=null&&reason.equals("occupied")&&!plan.replace()){var held=held();out.put("occupied",Map.of("count",held.size(),"first",held.stream().limit(8).map(WorkSpec::point).toList()));}
        // Where the stepped order stands: a job that ends unfinished stopped on this step, and nothing above it was begun.
        if(plan!=null){var step=plan.steps.receipt(buildStep,stepLeft,stepFirst);if(!step.isEmpty())out.put("step",step);}
        var blind=noVantage.stream().filter(p->!correct.getOrDefault(p,false)).limit(64).map(p->List.of(p.getX(),p.getY(),p.getZ())).toList();
        if(!blind.isEmpty())out.put("noVantage",blind);
        // This job's last path search found no way at all: the stall that follows then has its cause beside it.
        var pathing=engine.getPathingBehavior();
        if(!"succeeded".equals(state)&&pathing.calculationsStarted()>searchesBefore&&"FAILURE".equals(pathing.lastCalculation().get("type"))
            &&pathing.lastCalculation().get("search") instanceof Map<?,?> search)out.put("pathSearch",PathFailure.searchEnded(search.get("why"),null));
        return out;
    }
}
