// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh;

import dev.modbench.api.ControlRegistry;
import baritone.Baritone;
import dev.modbench.api.InputArbiter;
import dev.modbench.api.Navigation;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import java.util.*;
import java.util.function.Predicate;
import static baritone.gtnh.pathing.WorkSpec.*;

/** Bounded action lifetime around the source FollowProcess and its live goals. */
final class ReferenceFollowJob implements Navigation.Job {
    private final Minecraft mc=Minecraft.getMinecraft();
    private final Baritone engine;
    private final Object world=mc.theWorld,player=mc.thePlayer;
    private final String scope=ControlRegistry.memory().memory().scope();
    private final Map<baritone.api.Settings.Setting<?>,Object> saved=new LinkedHashMap<>();
    private final int duration;
    private final InputArbiter.Lease lease;
    private String state="following",reason="";
    private int ticks;
    private List<Integer> targets=List.of();
    private final Set<String> movements=new LinkedHashSet<>();
    ReferenceFollowJob(Baritone engine,Map<String,Object> params){
        this.engine=engine;
        duration=integer(params,"durationTicks",1200,1,72000);
        Predicate<Entity> filter=selector(child(params,"target"));
        int radius=integer(params,"radius",Baritone.settings().followRadius.value,0,64);
        double offset=number(params,"offsetDistance",Baritone.settings().followOffsetDistance.value,0,64);
        float direction=(float)number(params,"offsetDirection",Baritone.settings().followOffsetDirection.value,-360000,360000);
        boolean override=bool(params,"overrideProtection",false);
        var settings=Baritone.settings();
        for(var s:List.of(settings.allowBreak,settings.allowPlace,settings.followRadius,settings.followOffsetDistance,settings.followOffsetDirection))saved.put(s,s.value);
        engine.getPathingBehavior().forceCancel();
        lease=ControlRegistry.controls().arbiter().acquire("baritone-follow",this::cancel,override,true);
        settings.allowBreak.value=bool(params,"allowBreak",false);settings.allowPlace.value=bool(params,"allowPlace",false);
        settings.followRadius.value=radius;settings.followOffsetDistance.value=offset;settings.followOffsetDirection.value=direction;
        engine.overrideProtection=override;engine.positionAllowed=p->true;engine.explicitMiningTargets=()->s->false;
        engine.getInputOverrideHandler().attach(lease);engine.getFollowProcess().follow(filter);
    }
    private static Predicate<Entity> selector(Map<String,Object> target){
        if(target.isEmpty()||!Set.of("entityId","uuid","type","name").containsAll(target.keySet()))throw new IllegalArgumentException("follow target requires entityId, uuid, type or name; supplied fields all must match");
        Integer id=target.containsKey("entityId")?integer(target,"entityId",0,Integer.MIN_VALUE,Integer.MAX_VALUE):null;
        UUID uuid=target.containsKey("uuid")?UUID.fromString(target.get("uuid").toString()):null;
        String type=target.containsKey("type")?target.get("type").toString():null;
        String name=target.containsKey("name")?target.get("name").toString():null;
        return e->(id==null||id==e.getEntityId())&&(uuid==null||uuid.equals(e.getUniqueID()))&&
            (type==null||type.equals(net.minecraft.entity.EntityList.getEntityString(e)))&&(name==null||name.equals(e.getCommandSenderName()));
    }
    void tick(){
        if(done())return;
        if(mc.theWorld!=world||mc.thePlayer!=player||!scope.equals(ControlRegistry.memory().memory().scope())){cancel("world_changed");return;}
        if(!lease.isActive()){cancel("control_lost");return;}
        if(mc.currentScreen!=null&&!ControlRegistry.controls().ownsPlayerInventory(lease)){cancel("gui_open");return;}
        if(mc.thePlayer.isDead||mc.thePlayer.getHealth()<=0){cancel("player_unavailable");return;}
        if(!engine.getFollowProcess().isActive()){finish("failed","no_loaded_matching_entity");return;}
        targets=engine.getFollowProcess().following().stream().map(Entity::getEntityId).toList();
        if(ticks++>=duration){finish("succeeded","follow_duration_complete");return;}
        engine.tickStart();
        var path=engine.getPathingBehavior().getCurrent();
        if(path!=null)path.getPath().movements().forEach(m->movements.add(m.getClass().getSimpleName()));
    }
    private void finish(String state,String reason){
        if(done())return;this.state=state;this.reason=reason;
        engine.getPathingBehavior().forceCancel();engine.getInputOverrideHandler().release();
        saved.forEach(ReferenceSettings::copy);if(lease!=null)lease.close();
    }
    @Override public void cancel(String reason){finish("cancelled",reason);}
    @Override public boolean done(){return !state.equals("following");}
    @Override public boolean succeeded(){return state.equals("succeeded");}
    @Override public Map<String,Object> status(){
        return Map.of("engine","baritone-1.2.19-source-port","action","follow","state",state,"reason",reason,"ticks",ticks,
            "targetEntityIds",targets,"controlOwned",!done()&&lease.isActive(),"movementTypes",List.copyOf(movements),"scope",scope);
    }
}
