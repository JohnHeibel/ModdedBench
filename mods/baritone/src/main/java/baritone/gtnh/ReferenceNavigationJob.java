// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh;

import dev.modbench.api.ControlRegistry;
import baritone.Baritone;
import baritone.api.pathing.goals.*;
import baritone.api.pathing.calc.IPath;
import baritone.compat.BlockPos;
import dev.modbench.api.InputArbiter;
import dev.modbench.api.Navigation;
import net.minecraft.client.Minecraft;
import java.util.*;

/** Transport/action lifetime only. Planning and movement belong to the upstream processes. */
final class ReferenceNavigationJob implements Navigation.Job {
    private final Minecraft mc=Minecraft.getMinecraft();
    private final Baritone engine;
    private Goal goal;
    private final List<BlockPos> physicalGoals;
    private List<BlockPos> normalizedGoals;
    private int goalRenormalizations;
    private final Object world=mc.theWorld,player=mc.thePlayer;
    private final String scope=ControlRegistry.memory().memory().scope();
    private final boolean ownsLease,allowBreak,allowPlace,override;
    private final int timeout;
    private InputArbiter.Lease lease;
    private String state="planning",reason="";
    private int ticks,pathRevisions;
    private final long initialCalculations,initialSegments;
    private long calculations,segmentsCompleted;
    private IPath lastPath;
    private final LinkedHashSet<String> movements=new LinkedHashSet<>();
    private final List<Map<String,Object>> segmentHistory=new ArrayList<>();
    private final boolean previousAllowBreak,previousAllowPlace;
    private final baritone.gtnh.pathing.Stall stall=WorkAccess.stall(Map.of());
    ReferenceNavigationJob(Baritone engine,List<baritone.compat.BlockPos> goals,int timeout,boolean allowBreak,boolean allowPlace,boolean override,InputArbiter.Lease parent,baritone.compat.BlockPos corridorStart,double radius){
        this.engine=engine;this.timeout=timeout;this.allowBreak=allowBreak;this.allowPlace=allowPlace;this.override=override;
        physicalGoals=List.copyOf(goals);normalizedGoals=physicalGoals;
        refreshGoal();
        ownsLease=parent==null;
        previousAllowBreak=Baritone.settings().allowBreak.value;previousAllowPlace=Baritone.settings().allowPlace.value;
        engine.getPathingBehavior().forceCancel();
        initialCalculations=engine.getPathingBehavior().calculationsStarted();initialSegments=engine.getPathingBehavior().segmentsCompleted();
        lease=ownsLease?ControlRegistry.controls().arbiter().acquire("baritone-reference",this::cancel,override,true):parent;
        if(!lease.isActive())throw new IllegalArgumentException("navigation lease is inactive");
        Baritone.settings().allowBreak.value=allowBreak;Baritone.settings().allowPlace.value=allowPlace;
        engine.overrideProtection=override;
        engine.explicitMiningTargets=()->s->false;
        if(corridorStart!=null){
            var corridor=new baritone.gtnh.pathing.Corridor(corridorStart,goals.get(0),radius);
            engine.positionAllowed=p->corridor.contains(p);
        }else engine.positionAllowed=p->true;
        engine.getInputOverrideHandler().attach(lease);
        engine.getCustomGoalProcess().setGoalAndPath(goal);
    }
    private boolean refreshGoal(){
        var nativeWorld=engine.getPlayerContext().world();var next=new ArrayList<BlockPos>();
        for(int i=0;i<physicalGoals.size();i++)next.add(baritone.compat.NavigationCoordinates.goal(nativeWorld,physicalGoals.get(i),normalizedGoals.get(i)));
        if(goal!=null&&next.equals(normalizedGoals))return false;
        if(goal!=null)goalRenormalizations++;
        normalizedGoals=List.copyOf(next);var nodes=next.stream().map(GoalBlock::new).toArray(Goal[]::new);
        goal=nodes.length==1?nodes[0]:new GoalComposite(nodes);return true;
    }
    void tick(){
        if(done())return;
        if(WorkAccess.died(player)){finish("failed","player_died");return;}
        if(mc.theWorld!=world||mc.thePlayer!=player||!scope.equals(ControlRegistry.memory().memory().scope())){cancel("world_changed");return;}
        if(!lease.isActive()){cancel("control_lost");return;}
        if(mc.currentScreen!=null&&!dev.modbench.api.ControlRegistry.controls().ownsPlayerInventory(lease)){cancel("gui_open");return;}
        if(++ticks>timeout){finish("failed","timeout");return;}
        // Travel has no measure but new ground: a planner pacing or re-planning on the same few blocks is stuck.
        var feet=engine.getPlayerContext().playerFeet();
        if(stall.tick(0,feet.x,feet.y,feet.z)){finish("failed",stall.reason());return;}
        // A destination can first become observable hundreds of blocks after
        // this job starts. Let the source process revalidate the corrected goal.
        if(refreshGoal())engine.getCustomGoalProcess().setGoalAndPath(goal);
        engine.tickStart();
        var pathing=engine.getPathingBehavior();var current=pathing.getCurrent();
        if(current!=null&&current.getPath()!=lastPath){
            lastPath=current.getPath();pathRevisions++;
            lastPath.movements().forEach(m->movements.add(m.getClass().getSimpleName()));
            if(segmentHistory.size()<128)segmentHistory.add(Map.of("source",pos(lastPath.getSrc()),"destination",pos(lastPath.getDest()),"length",lastPath.length()));
        }
        state=current!=null?"moving":"planning";
        calculations=pathing.calculationsStarted()-initialCalculations;segmentsCompleted=pathing.segmentsCompleted()-initialSegments;
        if(!engine.getCustomGoalProcess().isActive()){
            if(goal.isInGoal(engine.getPlayerContext().playerFeet())){finish("succeeded","goal_reached");}
            else finish("failed","path_calculation_failed");
        }
    }
    void finish(String state,String reason){
        if(done())return;this.state=state;this.reason=reason;
        engine.getPathingBehavior().forceCancel();engine.getInputOverrideHandler().release();
        Baritone.settings().allowBreak.value=previousAllowBreak;Baritone.settings().allowPlace.value=previousAllowPlace;
        if(ownsLease&&lease!=null)lease.close();
    }
    @Override public void cancel(String reason){finish("cancelled",reason);}
    @Override public boolean done(){return Set.of("succeeded","failed","cancelled").contains(state);}
    @Override public boolean succeeded(){return state.equals("succeeded");}
    @Override public Map<String,Object> status(){
        Map<String,Object> result=new LinkedHashMap<>();var p=engine.getPathingBehavior();var current=p.getCurrent();
        result.put("engine","baritone-1.2.19-source-port");result.put("state",state);result.put("reason",reason);result.put("ticks",ticks);
        result.put("available",true);result.put("action","goto");
        result.put("goal",goal.toString());result.put("controlOwned",!done()&&lease!=null&&lease.isActive());
        result.put("allowBreak",allowBreak);result.put("allowPlace",allowPlace);result.put("overrideProtection",override);
        result.put("calculations",calculations);result.put("segmentsCompleted",segmentsCompleted);result.put("segmentHistory",List.copyOf(segmentHistory));
        result.put("pathRevisions",pathRevisions);result.put("stall",stall.status());
        result.put("goalRenormalizations",goalRenormalizations);
        result.put("movementTypes",List.copyOf(movements));result.put("nextSegmentReady",p.getNext()!=null);result.put("planning",p.getInProgress().isPresent());
        result.put("safeToCancel",p.isSafeToCancel());result.put("pathIndex",current==null?null:current.getPosition());
        result.put("path",lastPath==null?List.of():lastPath.positions().stream().map(ReferenceNavigationJob::pos).toList());
        result.put("scope",scope);return result;
    }
    private static List<Integer> pos(BlockPos p){return List.of(p.getX(),p.getY(),p.getZ());}
}
