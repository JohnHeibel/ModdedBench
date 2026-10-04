// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh;

import baritone.Baritone;
import baritone.api.Settings;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.schematic.ISchematic;
import baritone.compat.IBlockState;
import baritone.compat.StackIdentity;
import baritone.compat.BlockPos;
import baritone.gtnh.pathing.*;
import static baritone.gtnh.pathing.WorkSpec.*;
import java.util.*;

/**
 * The construction engine: native cell identity and durable intent around upstream BuilderProcess scheduling.
 * One behaviour. The plan is built in step order (BuildSteps), edits outside it need the terrain permissions,
 * and a job that cannot go on stops with one reason and, where one is to blame, one cell.
 */
final class ReferenceConstructionProcess extends BulkJob {
    private final Baritone engine;
    private final ConstructionPlan plan;
    private final Map<Settings.Setting<?>,Object> savedSettings=new LinkedHashMap<>();
    /** Made once for the plan: what the source builder is shown at each cell, and the item that places it. */
    private final Map<BlockPos,IBlockState> states=new HashMap<>();
    private final Map<BlockPos,Map<String,Object>> selectors=new HashMap<>();
    private Set<Map<String,Object>> kinds=Set.of();
    /** This tick: which loaded cells match. A new map every tick, never changed after: path searches read it. */
    private Map<BlockPos,Boolean> correct=Map.of();
    /** Clicks the game took into a cell that has not yet been seen to match: this session's only, gone when it does. */
    private final Map<BlockPos,Integer> attempts=new HashMap<>();
    /** The cell a stop is about, when one is to blame, and for missing_materials what is short. */
    private BlockPos blamed;
    private List<Map<String,Object>> missing=List.of();
    /** This tick: wrong cells of the steps so far that can still be worked, and the first that cannot (a block the job may not remove). */
    private int open;
    private BlockPos occupied;
    /** This tick: every wrong cell of the plan, counted, and the first few. */
    private int left;
    private List<BlockPos> leftFirst=List.of();
    /** This tick: cells that want a block and hold only what a placement would replace. */
    private Set<BlockPos> soft=Set.of();
    private final Set<BlockPos> pending=new HashSet<>();
    /** Cells to be emptied that were seen holding something: counted as removed when they are next seen empty. */
    private final Set<BlockPos> placedObserved=new HashSet<>(),removedObserved=new HashSet<>(),dirty=new HashSet<>();
    private boolean started;
    private final Map<Cell,Goal> placementGoals=new HashMap<>();
    private int placementGoalEpoch=-1;
    private BlockPos placementGoalFeet;
    /** The standing poses around the player that the cells near it share (WorkAccess.Stands), kept as long as the goals made from them. */
    private WorkAccess.Stands stands;
    /** How far from the player those poses are captured, and how far it may walk before they are captured again. */
    private static final int NEAR=10,DRIFT=2;
    private Set<BlockPos> deferredAir=Set.of();
    private boolean cleanupPhase;
    private boolean clearanceEgress;
    private Goal egressGoal;
    private final Map<BlockPos,Goal> cleanupGoals=new HashMap<>();
    private long searchesBefore=Long.MAX_VALUE;
    /** The current build step (BuildSteps), the one the running pass was started at, and this tick's wrong cells per step. */
    private int buildStep,passStep=-1;
    private int[] stepLeft=new int[0];
    private BlockPos[] stepFirst=new BlockPos[0];
    ReferenceConstructionProcess(BaritoneNavigation navigation,WorkJournal journal,Map<String,Object> options){
        super(navigation,journal,options);engine=navigation.reference();
        plan=new ConstructionPlan(params,journal.progress,world);
    }
    @Override void begin(){
        super.begin();
        for(Cell cell:plan.cells){
            BlockPos p=cell.pos();
            states.put(p,new IBlockState(cell.clear()?net.minecraft.init.Blocks.air:ConstructionPlan.block(cell),cell.meta(),null,p.getX(),p.getY(),p.getZ()));
            if(!cell.clear())selectors.put(p,Map.copyOf(ConstructionPlan.material(cell)));
        }
        kinds=Set.copyOf(selectors.values());
        survey();
        // A block the job may not remove can never become the plan's: say so now, with the cell, before any input.
        if(!plan.replace()){var held=held();if(!held.isEmpty()){blamed=held.get(0);finish("failed","occupied");return;}}
        engine.getPathingBehavior().forceCancel();engine.snags.reset();Cost.reset();searchesBefore=engine.getPathingBehavior().calculationsStarted();
        for(var setting:Baritone.settings().allSettings)savedSettings.put(setting,setting.value);
        initializeClearance();configure();engine.overrideProtection=override;engine.positionAllowed=p->true;
        engine.getInputOverrideHandler().attach(lease);
        capture();startPass();
    }
    /** Plan cells that want a block and hold another one, which a placement would not replace. */
    private List<BlockPos> held(){return plan.cells.stream().filter(c->!c.clear()&&plan.occupied(c.pos())&&!plan.correct(c)).map(Cell::pos).toList();}
    private void initializeClearance(){
        if(journal.progress.containsKey("deferredAir")){
            Set<BlockPos> restored=new HashSet<>();for(Object p:list(journal.progress.get("deferredAir")))restored.add(pos(p));
            Set<BlockPos> authorized=new HashSet<>();plan.cells.stream().filter(Cell::clear).forEach(c->authorized.add(c.pos()));
            if(!authorized.containsAll(restored))throw new IllegalArgumentException("saved clearance outside explicit air cells");
            deferredAir=Set.copyOf(restored);cleanupPhase=bool(journal.progress,"cleanupPhase",false);
            clearanceEgress=bool(journal.progress,"clearanceEgress",false);
        }else{
            deferredAir=DeferredClearance.capture(plan.cells,allowPlace,p->plan.loaded(p)&&world.isAirBlock(p.getX(),p.getY(),p.getZ()));
            journal.progress.put("deferredAir",deferredAir.stream().map(WorkSpec::point).toList());
            journal.progress.put("cleanupPhase",false);
            journal.progress.put("clearanceEgress",false);
        }
        journal.save(status());
    }
    /**
     * The plan is frozen and built one way: the source builder runs on its own defaults whatever the session's
     * settings say, in no order of its own (BuildSteps decides what it is shown), with the whole inventory.
     */
    private void configure(){
        var settings=Baritone.settings();
        for(String key:List.of("buildInLayers","layerOrder","layerHeight","startAtLayer","skipFailedLayers","breakFromAbove","goalBreakFromAbove","distanceTrim",
                "incorrectSize","builderTickScanRadius","breakCorrectBlockPenaltyMultiplier","buildIgnoreExisting","okIfWater","okIfAir","buildIgnoreBlocks","buildSkipBlocks",
                "buildIgnoreDirection","buildIgnoreProperties","buildSubstitutes","buildValidSubstitutes","mapArtMode","buildOnlySelection","buildRepeat",
                "schematicOrientationX","schematicOrientationY","schematicOrientationZ")){
            var setting=settings.byLowerName.get(key.toLowerCase(Locale.ROOT));ReferenceSettings.copy(setting,setting.defaultValue);
        }
        settings.buildRepeatCount.value=1;settings.allowInventory.value=true;
        settings.allowBreak.value=allowBreak;settings.allowPlace.value=allowPlace&&(!cleanupPhase||clearanceEgress);
    }
    /**
     * One walk over the plan, every tick: which cells match, what stands in those that do not, and the counts the
     * build order and the receipt are made of. This is the job's standing cost to the game thread (WorkSpec.CELLS).
     */
    private void survey(){
        Map<BlockPos,Boolean> matches=new HashMap<>(plan.cells.size()*2);Set<BlockPos> replaceable=new HashSet<>();List<BlockPos> some=new ArrayList<>();
        var order=plan.steps;int[] count=new int[order.count()];BlockPos[] first=new BlockPos[count.length];int walked=0,wrong=0;
        boolean replace=plan.replace();open=0;occupied=null;
        for(Cell cell:plan.cells){
            BlockPos p=cell.pos();int at=order.index(walked++);boolean done=false,stuck=false;
            if(plan.loaded(p)){
                done=plan.correct(cell);matches.put(p,done);
                // Seen to match: the clicks it took are forgotten, so a later repair of this cell starts from none.
                if(done&&pending.remove(p)){attempts.remove(p);placedObserved.add(p);}
                // A cell to be emptied may start empty, receive a scaffold and be cleared again: that removal counts too.
                if(cell.clear()){if(!done)dirty.add(p);else if(dirty.remove(p))removedObserved.add(p);}
                // What stands in a cell that wants a block: nothing or something a placement replaces (which the job may
                // also break, replace or not: it costs nothing that the placement would not), or a block in the way.
                else if(!done){
                    if(!baritone.compat.LegacyPlacement.empty(world,p.getX(),p.getY(),p.getZ()))stuck=!replace||pending.contains(p);
                    else if(!world.isAirBlock(p.getX(),p.getY(),p.getZ()))replaceable.add(p);
                }
            }
            if(done)continue;
            if(wrong++<ConstructionPlan.FIRST)some.add(p);
            if(at>=0&&count[at]++==0)first[at]=p;
            // The step is last tick's: it only moves on once everything before it is done, so nothing counted here is early.
            if(at<=buildStep){if(!stuck)open++;else if(occupied==null)occupied=p;}
        }
        correct=matches;soft=replaceable;left=wrong;leftFirst=some;
        stepLeft=count;stepFirst=first;buildStep=BuildSteps.current(buildStep,count);
    }
    /** The first cell still wrong among the steps so far, else the first wrong cell of the plan. */
    private BlockPos next(){
        for(int i=0;i<=buildStep&&i<stepLeft.length;i++)if(stepLeft[i]>0)return stepFirst[i];
        return leftFirst.isEmpty()?null:leftFirst.get(0);
    }
    private void capture(){
        BlockPos currentFeet=WorkAccess.feet();
        if(placementGoalEpoch!=ticks/20||!currentFeet.equals(placementGoalFeet)){
            if(placementGoalEpoch!=ticks/20)stands=null;
            placementGoalEpoch=ticks/20;placementGoalFeet=currentFeet;placementGoals.clear();cleanupGoals.clear();
        }
        survey();
        Map<Map<String,Object>,Set<StackIdentity>> carried=new HashMap<>();
        for(var selector:kinds){
            Set<StackIdentity> eligible=new HashSet<>();
            for(var stack:mc.thePlayer.inventory.mainInventory)if(stack!=null&&WorkAccess.item(stack,selector))eligible.add(StackIdentity.capture(stack));
            carried.put(selector,eligible);
        }
        // What the hooks below close over is read by path searches on their own threads: none of it changes after this.
        var cells=plan.schematic;var order=plan.steps;var verified=correct;var pendingSnapshot=Set.copyOf(pending);var softSnapshot=soft;
        int shown=buildStep;boolean replace=plan.replace(),clearing=cleanupPhase;
        var builder=engine.getBuilderProcess();
        builder.stateValidator=(current,wanted,itemVerify)->{
            BlockPos at=new BlockPos(wanted.x,wanted.y,wanted.z);Cell cell=cells.get(at);
            if(cell==null)return true;
            if(itemVerify){
                if(cell.clear())return current.getBlock()==net.minecraft.init.Blocks.air;
                var identity=current.placementIdentity();
                return identity!=null&&carried.getOrDefault(selectors.get(at),Set.of()).contains(identity);
            }
            return cell.verify().isEmpty()||verified.getOrDefault(new BlockPos(current.x,current.y,current.z),false);
        };
        // stateValidator already requires the cell's exact native item selector,
        // including metadata and NBT. A sample placement state (such as a lower
        // slab) must not make that material unavailable for another legal pose.
        // Only approximate inventory states use this; actual click prediction
        // and final world verification still require the requested block state.
        builder.approximateMaterialMatches=(current,wanted)->current.getBlock()==wanted.getBlock();
        builder.mayBreak=breakRule(cells,verified,clearing?Set.of():deferredAir,softSnapshot,pendingSnapshot,replace,allowBreak);
        // Outside the plan the terrain permissions decide: a scaffold or a bridge needs allowPlace, digging a way needs allowBreak.
        builder.mayPlace=p->allowPlace||cells.containsKey(new BlockPos(p.getX(),p.getY(),p.getZ()));
        // Nor may a walk bridge or pillar with anything, plan block or throwaway, into a cell whose step has not come.
        builder.movementMayPlace=p->order.visible(new BlockPos(p.getX(),p.getY(),p.getZ()),shown);
        // A cell that named no meta is satisfied by any variant of its block.
        builder.stateComparison=(actual,wanted)->{
            if(actual.getBlock()!=wanted.getBlock())return false;
            Cell cell=cells.get(new BlockPos(wanted.x,wanted.y,wanted.z));
            return cell!=null&&cell.anyMeta()||actual.meta==wanted.meta;
        };
        builder.placementGoalAdapter=(target,goal)->{
            Cell cell=cells.get(new BlockPos(target.getX(),target.getY(),target.getZ()));
            if(cell==null||cell.clear())return goal;
            if(placementGoals.containsKey(cell))return placementGoals.get(cell);
            int slot=plan.slot(cell);if(slot<0||!mc.thePlayer.onGround)return goal;
            // A cell out of reach of every pose near the player keeps the source goal, which is enough to walk towards it:
            // what can be seen of it from where is asked on arrival, so a plan costs the game by its surroundings, not its size.
            if(stands==null||!stands.within(currentFeet,DRIFT))stands=new WorkAccess.Stands(world,currentFeet,NEAR);
            if(!stands.covers(cell.pos())){placementGoals.put(cell,goal);return goal;}
            Set<BlockPos> legal=vantages(cell,slot,stands.around(cell.pos()),currentFeet);
            boolean adjacent=legal.stream().anyMatch(goal::isInGoal);
            // No existing vantage is not proof that construction is impossible:
            // the source planner may still build the support it needs to stand on.
            if(legal.isEmpty()){
                // Standing in the cell itself fails native collision from every vantage, and the source goal (stand on top of the
                // new block) is unreachable without scaffolding: step out to a neighbouring column first, then this adapter runs again.
                var at=cell.pos();
                if(!inside(at)){placementGoals.put(cell,goal);return goal;}
                // A low neighbouring stance can still overlap a different floor
                // cell, while searchForPlaceables refuses every upward click.
                // Egress must reach a height where this cell becomes actionable.
                boolean covered=world.getBlock(at.getX(),at.getY()+1,at.getZ())!=net.minecraft.init.Blocks.air;int up=reachUp();
                var out=stands.around(at).stream().map(pose->pose.feet()).filter(f->PlacementGoalSupport.egress(at,f,covered,up)&&ForgeSnapshot.liveStandable(world,f))
                    .map(f->(Goal)new GoalBlock(f)).toArray(Goal[]::new);
                return out.length==0?goal:new GoalComposite(out);
            }
            // Existing work poses supplement the source goal, never replace it: removing a future goal would keep A*
            // from constructing its own support (notably a pillar), even though a distant existing pose is legal.
            Goal adapted=adjacent?goal:new GoalComposite(goal,new GoalComposite(legal.stream().map(p->new baritone.process.BuilderProcess.GoalPlace(p.down())).toArray(Goal[]::new)));
            placementGoals.put(cell,adapted);return adapted;
        };
        builder.breakGoalAdapter=(target,goal)->{
            BlockPos p=new BlockPos(target.getX(),target.getY(),target.getZ());
            // Upstream's break goal ends two below the block; the hand reaches further up (toBreakNearPlayer looks five above the feet),
            // so standing under it counts too. The ray at the click still decides.
            if(!cleanupPhase||!deferredAir.contains(p))return new GoalComposite(goal,new GoalBlock(p.down(3)),new GoalBlock(p.down(4)),new GoalBlock(p.down(5)));
            return cleanupGoals.computeIfAbsent(p,key->{
                List<Goal> goals=new ArrayList<>();goals.add(goal);
                for(var pose:WorkAccess.buildingApproaches(world,p)){
                    var feet=pose.feet();int dy=p.getY()-feet.getY();
                    // Match source toBreakNearPlayer's actionable height range.
                    if(dy<0||dy>5||feet.equals(p)||!ForgeSnapshot.liveStandable(world,feet))continue;
                    var eye=feet.equals(currentFeet)?mc.thePlayer.getPosition(1):WorkAccess.eyeAt(pose);
                    if(MiningJob.reachable(mc,world,p,eye)!=null)goals.add(new GoalBlock(feet));
                }
                return new GoalComposite(goals.toArray(Goal[]::new));
            });
        };
        if(clearanceEgress&&!DeferredClearance.needsEgress(deferredAir,p->correct.getOrDefault(p,false))){
            // A paused job may be resumed after another actor cleared its supports.
            clearanceEgress=false;journal.progress.put("clearanceEgress",false);configure();
        }
        if(clearanceEgress&&egressGoal==null){
            Set<BlockPos> safe=new HashSet<>();
            for(BlockPos p:deferredAir)if(!correct.getOrDefault(p,false))
                for(var pose:WorkAccess.buildingApproaches(world,p)){
                    var feet=pose.feet();
                    if(feet.getY()>p.getY()||deferredAir.contains(new BlockPos(feet.getX(),feet.getY()-1,feet.getZ()))||!ForgeSnapshot.liveStandable(world,feet))continue;
                    if(MiningJob.reachable(mc,world,p,WorkAccess.eyeAt(pose))!=null)safe.add(feet);
                }
            if(safe.isEmpty())throw new IllegalStateException("no_observed_clearance_egress_pose");
            egressGoal=new GoalComposite(safe.stream().map(GoalBlock::new).toArray(Goal[]::new));
        }
        builder.accessGoal=()->clearanceEgress?egressGoal:null;
        // Immutable explicit permissions match exact observed states inside the plan only.
        Map<BlockPos,IBlockState.StateKey> breaks=new HashMap<>();
        if(left>0)for(Cell cell:plan.cells)if((replace||cell.clear()||soft.contains(cell.pos()))&&Boolean.FALSE.equals(correct.get(cell.pos()))&&!pending.contains(cell.pos())){
            BlockPos p=cell.pos();breaks.put(p,new IBlockState.StateKey(world.getBlock(p.getX(),p.getY(),p.getZ()),world.getBlockMetadata(p.getX(),p.getY(),p.getZ())));
        }
        engine.explicitMiningTargets=()->state->state.key().equals(breaks.get(new BlockPos(state.x,state.y,state.z)));
        builder.beforePlace=p->{
            BlockPos pos=new BlockPos(p.getX(),p.getY(),p.getZ());Cell cell=cells.get(pos);
            if(cell==null||cell.clear()){
                // Source BuilderCalculationContext deliberately permits temporary
                // supports where the final schematic wants air. Validate the
                // selected throwaway, not the finished cell's material selector.
                // Explicit-air verification still requires their removal.
                var held=mc.thePlayer.getHeldItem();
                if(cleanupPhase&&!clearanceEgress||!allowPlace||held==null||!(held.getItem() instanceof net.minecraft.item.ItemBlock)
                        ||!engine.getInventoryBehavior().isGenericThrowaway(held))
                    throw new IllegalStateException(cell==null&&!allowPlace?"placement outside the plan without allowPlace":"temporary support material changed before placement");
                return;
            }
            if(!WorkAccess.item(mc.thePlayer.getHeldItem(),selectors.get(pos)))throw new IllegalStateException("schematic material changed before placement");
            var hit=baritone.compat.RayTraceResult.fromNative(mc.objectMouseOver);
            if(hit==null||hit.typeOfHit!=baritone.compat.RayTraceResult.Type.BLOCK)throw new IllegalStateException("schematic placement ray disappeared");
            // The click the game is about to take would make another variant than the one asked: nothing is placed.
            var predicted=baritone.compat.LegacyPlacement.predict(engine.getPlayerContext(),mc.thePlayer.getHeldItem(),hit,engine.getPlayerContext().playerRotations());
            if(!builder.stateComparison.test(predicted,states.get(pos))){blamed=pos;finish("paused","mismatch");return;}
            if(attempts.getOrDefault(pos,0)>=ConstructionPlan.ATTEMPTS){blamed=pos;finish("paused","attempt_limit");}
        };
        // Charged only for a click the game took. One it refused placed nothing, and is not the cell's fault.
        engine.getPlayerContext().playerController().placed=p->{
            BlockPos pos=new BlockPos(p.getX(),p.getY(),p.getZ());Cell cell=cells.get(pos);if(cell==null||cell.clear())return;
            int count=attempts.merge(pos,1,Integer::sum);journal.recordAttempt(ConstructionPlan.key(cell),count);pending.add(pos);
            placementGoals.clear();stands=null;
        };
    }
    /** Whether the player's body is in this cell. */
    private boolean inside(BlockPos at){
        return mc.thePlayer.boundingBox.intersectsWith(net.minecraft.util.AxisAlignedBB.getBoundingBox(at.getX(),at.getY(),at.getZ(),at.getX()+1,at.getY()+1,at.getZ()+1));
    }
    /** The feet positions, among these poses, from which a face to place this cell against is in view. */
    private Set<BlockPos> vantages(Cell cell,int slot,List<WorkAccess.Pose> poses,BlockPos currentFeet){
        var builder=engine.getBuilderProcess();Set<BlockPos> legal=new HashSet<>();
        for(var pose:poses){
            var sourceFeet=baritone.compat.NavigationCoordinates.feet(pose.feet().getX()+.5,pose.standingY(),pose.feet().getZ()+.5,
                p->world.getBlock(p.getX(),p.getY(),p.getZ()) instanceof net.minecraft.block.BlockSlab);
            if(!sourcePlacementHeight(cell,sourceFeet))continue;
            // PathExecutor reaches a block goal before necessarily reaching
            // its center. At the current cell use the real body position:
            // otherwise a boundary-overlapping player can be declared ready
            // to place its neighbor forever, while native collision rejects it.
            boolean here=pose.feet().equals(currentFeet);
            double y=here?mc.thePlayer.boundingBox.minY:pose.standingY();
            PlacementGoalSupport.Probe probe=(x,z)->builder.canPlaceFrom(states.get(cell.pos()),x,y,z,slot);
            // Any other cell is promised from its centre, where the player will not be standing. A view that exists
            // only from the exact centre (a ray through the seam of two blocks meeting at an edge) is no stance:
            // on arrival the real position refutes it and the next such cell becomes the goal, back and forth.
            if(here?probe.at(mc.thePlayer.posX,mc.thePlayer.posZ):PlacementGoalSupport.steady(pose.feet().getX()+.5,pose.feet().getZ()+.5,probe))legal.add(sourceFeet);
        }
        return legal;
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
    private boolean sourcePlacementHeight(Cell cell,BlockPos sourceFeet){
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
        // Only what the source builder is working on may request a placement pose.
        // Future roof cells must not pull the player back from a wall traversal.
        for(var target:engine.getBuilderProcess().incorrectPositions()){
            Cell cell=plan.schematic.get(new BlockPos(target.getX(),target.getY(),target.getZ()));if(cell==null)continue;
            if(cell.clear()||correct.getOrDefault(cell.pos(),false)||!sourcePlacementHeight(cell,feet))continue;
            var p=cell.pos();
            if(!mc.thePlayer.boundingBox.intersectsWith(net.minecraft.util.AxisAlignedBB.getBoundingBox(p.getX(),p.getY(),p.getZ(),p.getX()+1,p.getY()+1,p.getZ()+1)))continue;
            int slot=plan.slot(cell);if(slot<0)continue;
            var wanted=states.get(p);
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
            placementGoals.clear();state="positioning";return true;
        }
        return false;
    }
    private void startPass(){
        if(plan.cells.isEmpty()){finish("succeeded","empty_selected_schematic");return;}
        int minX=plan.cells.stream().mapToInt(c->c.pos().getX()).min().orElseThrow(),minY=plan.cells.stream().mapToInt(c->c.pos().getY()).min().orElseThrow(),minZ=plan.cells.stream().mapToInt(c->c.pos().getZ()).min().orElseThrow();
        int width=plan.cells.stream().mapToInt(c->c.pos().getX()).max().orElseThrow()-minX+1,height=plan.cells.stream().mapToInt(c->c.pos().getY()).max().orElseThrow()-minY+1,length=plan.cells.stream().mapToInt(c->c.pos().getZ()).max().orElseThrow()-minZ+1;
        // The source builder is shown the plan up to the current step only; step() starts a new pass when the step moves on.
        Map<BlockPos,IBlockState> frozen=plan.steps.schematic(DeferredClearance.schematic(states,deferredAir,cleanupPhase),buildStep);
        passStep=buildStep;
        ISchematic schematic=new ISchematic(){
            public int widthX(){return width;}public int heightY(){return height;}public int lengthZ(){return length;}
            public boolean inSchematic(int x,int y,int z,IBlockState current){return frozen.containsKey(new BlockPos(x+minX,y+minY,z+minZ));}
            public IBlockState desiredState(int x,int y,int z,IBlockState current,List<IBlockState> materials){
                return frozen.getOrDefault(new BlockPos(x+minX,y+minY,z+minZ),current);
            }
        };
        engine.bsi=new baritone.utils.BlockStateInterface(engine.getPlayerContext());
        engine.getBuilderProcess().build(String.valueOf(params.getOrDefault("name","ModdedBench schematic")),schematic,new baritone.compat.Vec3i(minX,minY,minZ));
        started=true;
    }
    @Override int progress(){return placedObserved.size()+removedObserved.size();}
    // A cell nothing can be placed against, or one the player cannot leave, keeps the source builder at its goal or
    // replanning for ever; the shared watchdog ends that. A pending placement or a block cleared is work too, and so is
    // a break the game is advancing: a slow block (a worn tool, a hard block in the way) is no stall, one that never breaks is.
    @Override long activity(){return java.util.Objects.hash(placedObserved.size(),removedObserved.size(),pending.size(),baritone.compat.LegacyPlayerController.damage());}
    @Override String phase(){return "reference_build";}
    /**
     * The shared deadline and watchdog, in the builder's own few words and with a cell. A stall is no_stance when
     * every cell that could still be worked has no standing spot from which a face to place it against is in view,
     * no_route when the last path search covered everything reachable and found no way, else plain stalled.
     */
    @Override String named(String why){
        if(why.startsWith("timeout_"))return "timeout";
        if(!why.startsWith("stalled_"))return why;
        // Asked once, now: of the cells that could still be worked, has every one no such spot?
        BlockPos blind=null;BlockPos feet=WorkAccess.feet();
        for(Cell cell:plan.cells){
            BlockPos p=cell.pos();
            if(!Boolean.FALSE.equals(correct.get(p))||!plan.steps.visible(p,buildStep))continue;
            boolean held=!cell.clear()&&!baritone.compat.LegacyPlacement.empty(world,p.getX(),p.getY(),p.getZ());
            if(held&&(!plan.replace()||pending.contains(p)))continue;
            int slot=cell.clear()||held?-1:plan.slot(cell);
            if(slot<0||inside(p)||!vantages(cell,slot,WorkAccess.buildingApproaches(world,p),feet).isEmpty()){blind=null;break;}
            if(blind==null)blind=p;
        }
        if(blind!=null){blamed=blind;return "no_stance";}
        blamed=next();
        var pathing=engine.getPathingBehavior();
        return pathing.calculationsStarted()>searchesBefore&&"FAILURE".equals(pathing.lastCalculation().get("type"))
            &&pathing.lastCalculation().get("search") instanceof Map<?,?> search&&"exhausted".equals(search.get("why"))?"no_route":"stalled";
    }
    @Override void step(){
        if(!started)return;
        capture();var builder=engine.getBuilderProcess();
        if(clearanceEgress&&mc.thePlayer.onGround&&egressGoal.isInGoal(engine.getPlayerContext().playerFeet())){
            clearanceEgress=false;journal.progress.put("clearanceEgress",false);
            engine.getPathingBehavior().forceCancel();configure();capture();startPass();journal.save(status());return;
        }
        // Nothing left that can be placed or cleared, and a block the job may not remove stands in a cell: no walk ends this.
        // One this job clicked in itself came out as another variant than the plan's (a facing, say): that is a mismatch.
        if(open==0&&occupied!=null){blamed=occupied;finish(session()>0?"paused":"failed",pending.contains(occupied)?"mismatch":"occupied");return;}
        // The build step moved on (the source builder may already have stopped, its shown cells all done). Existing
        // searches retain their immutable schematic; start a new source plan.
        if(passStep!=buildStep){engine.getPathingBehavior().forceCancel();startPass();}
        if(!builder.isActive()){
            // The source builder holds every cell it was shown done. What the plan still finds wrong is a cell it cannot make as asked.
            if(!cleanupPhase&&!deferredAir.isEmpty()){
                var wrong=plan.cells.stream().filter(c->!deferredAir.contains(c.pos())&&!plan.correct(c)).findFirst();
                if(wrong.isPresent()){blamed=wrong.get().pos();finish("paused","mismatch");return;}
                clearanceEgress=DeferredClearance.needsEgress(deferredAir,p->correct.getOrDefault(p,false));
                cleanupPhase=true;journal.progress.put("cleanupPhase",true);journal.progress.put("clearanceEgress",clearanceEgress);
                engine.getPathingBehavior().forceCancel();configure();capture();startPass();journal.save(status());return;
            }
            if(left>0){blamed=next();finish("paused","mismatch");return;}
            finish("succeeded","schematic_verified");return;
        }
        if(builder.isPaused()){
            // The source builder has nothing it can do for the cells it is shown: nothing carried goes into any of them.
            Map<Map<String,Object>,Integer> required=new LinkedHashMap<>();
            for(Cell c:plan.cells)if(!c.clear()&&!correct.getOrDefault(c.pos(),false)){
                required.merge(selectors.get(c.pos()),1,Integer::sum);
                if(blamed==null&&plan.steps.visible(c.pos(),buildStep)&&!plan.occupied(c.pos())&&plan.slot(c)<0)blamed=c.pos();
            }
            if(blamed==null){blamed=next();finish("paused","stalled");return;}
            missing=ConstructionPlan.materials(required).stream().filter(row->(Integer)row.get("missing")>0).limit(2*ConstructionPlan.FIRST).toList();
            finish("paused","missing_materials");return;
        }
        if(centerForPlacement())return;
        engine.tickStart();
        // Placement callbacks may finish and release this job during the source tick.
        if(done())return;
        state="building";
        if(ticks%20==0)journal.save(status());
    }
    @Override void releaseProcess(){
        engine.getPathingBehavior().forceCancel();engine.getInputOverrideHandler().release();engine.getBuilderProcess().resetAdapters();engine.getPlayerContext().playerController().placed=p->{};engine.explicitMiningTargets=()->s->false;
        engine.overrideProtection=false;engine.positionAllowed=p->true;
        for(var entry:savedSettings.entrySet())ReferenceSettings.copy(entry.getKey(),entry.getValue());
    }
    @Override public Map<String,Object> status(){
        var out=super.status();out.remove("serverAcknowledged");
        out.put("buildPhase",clearanceEgress?"clearance_egress":cleanupPhase?"clearance":"construction");out.put("deferredAirCells",deferredAir.size());
        out.put("selected",plan.cells.size());out.put("placed",placedObserved.size());out.put("removed",removedObserved.size());out.put("pendingPlacementVerification",pending.size());
        // One stop, one reason, one cell where a cell is to blame, and the step it happened on.
        if(done()&&!succeeded()){
            Map<String,Object> stop=new LinkedHashMap<>();stop.put("reason",reason);
            if(blamed!=null)stop.put("pos",point(blamed));
            var at=plan.steps.where(blamed,buildStep);if(!at.isEmpty())stop.put("step",at);
            out.put("stopped",stop);
            if(!missing.isEmpty())out.put("missing",missing);
            if(reason.equals("occupied")&&!plan.replace()){var held=held();out.put("occupied",Map.of("count",held.size(),"first",held.stream().limit(ConstructionPlan.FIRST).map(WorkSpec::point).toList()));}
        }
        // What is still wrong, and where the stepped order stands: a job that ends unfinished stopped on this step, and nothing above it was begun.
        out.put("left",Map.of("count",left,"first",leftFirst.stream().map(WorkSpec::point).toList()));
        var step=plan.steps.receipt(buildStep,stepLeft,stepFirst);if(!step.isEmpty())out.put("step",step);
        out.put("cost",Cost.status());
        return out;
    }
}
