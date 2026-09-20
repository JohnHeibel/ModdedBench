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
final class ReferenceProcessJob implements Navigation.Job {
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
    private final Set<String> movements=new LinkedHashSet<>();
    ReferenceProcessJob(Baritone engine,Map<String,Object> params){
        this.engine=engine;kind=String.valueOf(params.get("process"));
        duration=integer(params,"durationTicks",1200,1,72000);
        goal=kind.equals("goal")?ReferenceGoals.parse(child(params,"goal")):null;
        var feet=engine.getPlayerContext().playerFeet();
        var center=params.containsKey("center")?pos(params.get("center")):new baritone.compat.BlockPos(feet.x,feet.y,feet.z);
        int radius=integer(params,"radius",24,1,64);
        var blockSpec=child(params,"block");
        BlockOptionalMeta block=kind.equals("get_to_block")?new BlockOptionalMeta(String.valueOf(blockSpec.get("id"))+(blockSpec.containsKey("meta")?":"+integer(blockSpec,"meta",0,0,15):"")):null;
        process=switch(kind){case "goal"->engine.getCustomGoalProcess();case "explore"->engine.getExploreProcess();case "get_to_block"->engine.getGetToBlockProcess();case "farm"->engine.getFarmProcess();default->throw new IllegalArgumentException("process must be goal, explore, get_to_block or farm");};
        if(kind.equals("explore")&&engine.getWorldProvider().getCurrentWorld()==null)throw new IllegalArgumentException("exploration requires the server world identity and cache");
        var settings=Baritone.settings();
        for(var s:List.of(settings.allowBreak,settings.allowPlace,settings.exploreForBlocks,settings.rightClickContainerOnArrival,settings.enterPortal))saved.put(s,s.value);
        engine.getPathingBehavior().forceCancel();
        try{
            lease=ControlRegistry.controls().arbiter().acquire("baritone-"+kind,this::cancel,bool(params,"overrideProtection",false),true);
            settings.allowBreak.value=bool(params,"allowBreak",false);settings.allowPlace.value=bool(params,"allowPlace",false);
            settings.exploreForBlocks.value=bool(params,"exploreForBlocks",true);
            settings.rightClickContainerOnArrival.value=bool(params,"openOnArrival",false);settings.enterPortal.value=bool(params,"enterPortal",false);
            engine.overrideProtection=bool(params,"overrideProtection",false);engine.positionAllowed=p->true;engine.explicitMiningTargets=()->s->false;
            engine.getInputOverrideHandler().attach(lease);engine.bsi=new baritone.utils.BlockStateInterface(engine.getPlayerContext());
            switch(kind){
                case "goal"->engine.getCustomGoalProcess().setGoalAndPath(goal);
                case "explore"->engine.getExploreProcess().explore(center.getX(),center.getZ());
                case "get_to_block"->engine.getGetToBlockProcess().getToBlock(block);
                case "farm"->engine.getFarmProcess().farm(radius,center);
            }
        }catch(RuntimeException failure){finish("failed","start_failed");throw failure;}
    }
    void tick(){
        if(done())return;
        if(mc.theWorld!=world||mc.thePlayer!=player||!scope.equals(ControlRegistry.memory().memory().scope())){cancel("world_changed");return;}
        if(!lease.isActive()){cancel("control_lost");return;}
        if(mc.currentScreen!=null&&!ControlRegistry.controls().ownsPlayerInventory(lease)){cancel("gui_open");return;}
        if(mc.thePlayer.isDead||mc.thePlayer.getHealth()<=0){cancel("player_unavailable");return;}
        if(ticks++>=duration){finish(kind.equals("farm")||kind.equals("explore")?"succeeded":"failed","duration_complete");return;}
        engine.tickStart();var current=engine.getPathingBehavior().getCurrent();
        if(current!=null)current.getPath().movements().forEach(m->movements.add(m.getClass().getSimpleName()));
        if(!process.isActive()){
            boolean success=switch(kind){case "goal"->goal.isInGoal(engine.getPlayerContext().playerFeet());case "get_to_block"->engine.getGetToBlockProcess().arrived;case "explore"->engine.getExploreProcess().completed;default->false;};
            finish(success?"succeeded":"failed",success?"source_process_complete":"source_process_stopped");
        }
    }
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
        out.put("ticks",ticks);out.put("controlOwned",!done()&&lease!=null&&lease.isActive());out.put("scope",scope);out.put("movementTypes",List.copyOf(movements));
        out.put("goal",String.valueOf(engine.getPathingBehavior().getGoal()));return out;
    }
}
