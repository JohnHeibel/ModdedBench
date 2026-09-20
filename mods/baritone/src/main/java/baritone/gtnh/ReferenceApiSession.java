// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh;

import dev.modbench.api.ControlRegistry;
import baritone.Baritone;
import dev.modbench.api.InputArbiter;
import java.util.Map;
import net.minecraft.client.Minecraft;

/** Runs Java API processes under the same lifetime and protection as MCP jobs. */
final class ReferenceApiSession {
    private final Baritone engine;
    private InputArbiter.Lease lease;
    private Object world,player;
    private String scope,reason="idle";
    private int ticks;
    ReferenceApiSession(Baritone engine){
        this.engine=engine;
        engine.getGameEventHandler().registerEventListener(new baritone.api.event.listener.AbstractGameEventListener(){
            public void onWorldEvent(baritone.api.event.events.WorldEvent event){
                if(active()&&event.getState()==baritone.api.event.events.type.EventState.PRE)stop("world_changed");
            }
        });
    }
    boolean active(){return lease!=null;}
    Map<String,Object> status(){return Map.of("action","java_api","state",active()?"running":"idle","reason",reason,"ticks",ticks,"controlOwned",active()&&lease.isActive());}
    void tick(){
        var mc=Minecraft.getMinecraft();
        if(active()){
            if(!lease.isActive()||world!=mc.theWorld||player!=mc.thePlayer||!scope.equals(ControlRegistry.memory().memory().scope())){stop("world_or_control_changed");return;}
            if(mc.thePlayer.isDead||mc.thePlayer.getHealth()<=0||mc.currentScreen!=null&&!ControlRegistry.controls().ownsPlayerInventory(lease)){stop("player_unavailable");return;}
        }else {
            if(mc.thePlayer==null||mc.theWorld==null||!engine.getPathingControlManager().hasActiveProcess())return;
            // Implicit getter activation must never steal another primitive's input.
            if(ControlRegistry.controls().arbiter().current().active()||mc.currentScreen!=null||mc.thePlayer.getHealth()<=0){engine.getPathingBehavior().forceCancel();reason="control_busy";return;}
            world=mc.theWorld;player=mc.thePlayer;scope=ControlRegistry.memory().memory().scope();ticks=0;reason="active";
            lease=ControlRegistry.controls().arbiter().acquire("baritone-java-api",this::stop,false,true);
            engine.overrideProtection=false;engine.positionAllowed=p->true;engine.explicitMiningTargets=()->s->false;
            engine.getInputOverrideHandler().attach(lease);
        }
        try {
            ticks++;engine.tickStart();
            if(!engine.getPathingControlManager().hasActiveProcess()&&!engine.getPathingBehavior().isPathing())stop("source_process_complete");
        }catch(RuntimeException error){stop("game_error: "+error.getClass().getSimpleName()+": "+error.getMessage());}
    }
    void stop(String reason){
        InputArbiter.Lease old=lease;lease=null;this.reason=reason;
        engine.getPathingBehavior().forceCancel();engine.getInputOverrideHandler().release();
        engine.overrideProtection=false;engine.positionAllowed=p->true;engine.explicitMiningTargets=()->s->false;
        if(old!=null)old.close();
    }
}
