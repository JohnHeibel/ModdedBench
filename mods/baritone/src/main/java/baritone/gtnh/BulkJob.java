// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh;

import dev.modbench.api.ControlRegistry;
import dev.modbench.api.InputArbiter;
import dev.modbench.api.Navigation;
import baritone.gtnh.pathing.*;
import static baritone.gtnh.pathing.WorkSpec.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.world.World;

/** One owned process across movement, inventory and block-work child jobs. */
abstract class BulkJob implements Navigation.Job {
    final Minecraft mc=Minecraft.getMinecraft();
    final World world=mc.theWorld;
    final Object player=mc.thePlayer;
    final BaritoneNavigation navigation;
    final WorkJournal journal;
    final Map<String,Object> params;
    final boolean override,allowBreak,allowPlace;
    final float health=mc.thePlayer.getHealth();
    int remaining,ticks;
    String state="preparing",reason="";
    InputArbiter.Lease lease;
    Navigation.Job child;
    final List<Map<String,Object>> history=new ArrayList<>();
    BulkJob(BaritoneNavigation navigation,WorkJournal journal,Map<String,Object> options) {
        this.navigation=navigation;this.journal=journal;params=new LinkedHashMap<>(journal.spec);params.putAll(options);
        override=bool(options,"overrideProtection",false);allowBreak=bool(params,"allowBreak",false);allowPlace=bool(params,"allowPlace",false);
        remaining=integer(params,"timeoutTicks",12000,1,72000);
    }
    void begin() {
        lease=ControlRegistry.controls().arbiter().acquire("baritone_"+journal.kind,this::cancel,override,true);
        journal.save(status());
    }
    final void tick() {
        if(done())return;
        try {
            if(mc.theWorld!=world||mc.thePlayer!=player||!journal.scope.equals(ControlRegistry.memory().memory().scope())){cancel("world_or_player_changed");return;}
            if(!lease.isActive()){cancel("superseded");return;}
            if(mc.currentScreen!=null&&!ControlRegistry.controls().ownsPlayerInventory(lease)||mc.thePlayer.getHealth()<=0){cancel("gui_or_death");return;}
            if(--remaining<=0){finish("failed","timeout");return;}ticks++;
            if(mc.thePlayer.getHealth()<health||mc.thePlayer.isBurning()||mc.thePlayer.getAir()<120){finish("failed","damage_fire_or_low_air");return;}
            boolean tickedChild=false;
            for(int transitions=0;transitions<8&&!done();transitions++) {
                if(child!=null&&!child.done()) {
                    if(tickedChild)return;
                    tickedChild=true;navigation.tickChild(child);if(!child.done())return;
                }
                var oldChild=child;String oldPhase=phase();step();
                if(oldChild==child&&oldPhase.equals(phase()))return;
            }
        }catch(Exception|LinkageError error){finish("failed",error.getClass().getSimpleName()+": "+error.getMessage());}
    }
    abstract void step();
    abstract String phase();
    void releaseProcess() {}
    void move(BlockPos p) {child=navigation.travel(p,Math.min(remaining,1200),allowBreak,allowPlace,override,lease);}
    Map<String,Object> consumeChild() {
        Map<String,Object> receipt=child.status();if(history.size()>=32)history.remove(0);history.add(receipt);child=null;return receipt;
    }
    final void finish(String terminal,String why) {
        if(done())return;state=terminal;reason=why;
        try {if(child!=null&&!child.done())child.cancel(why);}finally{try{releaseProcess();}finally{if(lease!=null)lease.close();}}
        journal.progress.put("lastTicks",ticks);
        try{journal.save(status());}catch(Exception error){state="failed";reason+="; checkpoint_failed: "+error.getMessage();}
    }
    @Override public void cancel(String reason){finish("cancelled",reason);}
    @Override public boolean done(){return Set.of("succeeded","failed","cancelled","paused").contains(state);}
    @Override public boolean succeeded(){return state.equals("succeeded");}
    @Override public Map<String,Object> status() {
        Map<String,Object> out=new LinkedHashMap<>();out.put("available",true);out.put("action",journal.kind);out.put("jobId",journal.id);out.put("state",state);out.put("reason",reason);out.put("ticks",ticks);out.put("remainingTicks",remaining);
        out.put("overrideProtection",override);out.put("scope",journal.scope);out.put("controlOwned",lease!=null&&lease.isActive());out.put("child",child==null?null:child.status());out.put("recentWork",history);out.put("serverAcknowledged",false);return out;
    }
}
