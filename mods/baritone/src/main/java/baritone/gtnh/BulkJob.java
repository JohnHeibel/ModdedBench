// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
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

/** One owned process: a control lease, a journal, a tick budget and a terminal state. */
abstract class BulkJob implements Navigation.Job {
    final Minecraft mc=Minecraft.getMinecraft();
    final World world=mc.theWorld;
    final Object player=mc.thePlayer;
    final BaritoneNavigation navigation;
    final WorkJournal journal;
    final Map<String,Object> params;
    final boolean override,allowBreak,allowPlace;
    final float health=mc.thePlayer.getHealth();
    int remaining,ticks,stillTicks,progressSeen,sessionStart;
    double stallX,stallY,stallZ;
    String state="preparing",reason="";
    InputArbiter.Lease lease;
    BulkJob(BaritoneNavigation navigation,WorkJournal journal,Map<String,Object> options) {
        this.navigation=navigation;this.journal=journal;params=new LinkedHashMap<>(journal.spec);params.putAll(options);
        override=bool(options,"overrideProtection",false);allowBreak=bool(params,"allowBreak",false);allowPlace=bool(params,"allowPlace",false);
        remaining=integer(params,"timeoutTicks",12000,1,72000);
    }
    void begin() {
        lease=ControlRegistry.controls().arbiter().acquire("baritone_"+journal.kind,this::cancel,override,true);
        sessionStart=progress();
        journal.save(status());
    }
    final void tick() {
        if(done())return;
        try {
            if(mc.theWorld!=world||mc.thePlayer!=player||!journal.scope.equals(ControlRegistry.memory().memory().scope())){cancel("world_or_player_changed");return;}
            if(!lease.isActive()){cancel("superseded");return;}
            if(mc.thePlayer.getHealth()<=0){finish("failed","player_died");return;}
            if(mc.currentScreen!=null&&!ControlRegistry.controls().ownsPlayerInventory(lease)){cancel("gui_opened");return;}
            // The deadline is a budget, not a verdict: a job that has produced something stops as paused, with its rate in the
            // receipt, and mb_work_resume continues it. A session that produced nothing has failed, whatever earlier ones did,
            // so resuming a stuck job cannot come back paused for ever.
            if(--remaining<=0){finish(session()>0?"paused":"failed",session()>0?"timeout_with_progress":"timeout_without_progress");return;}ticks++;
            // The upstream engine re-plans for ever around a target it cannot reach (bobbing in a pond, say): forty seconds
            // within two blocks of one spot with nothing to show for it is that, whatever the planner believes.
            if(progress()!=progressSeen||mc.thePlayer.getDistanceSq(stallX,stallY,stallZ)>4){progressSeen=progress();stillTicks=0;stallX=mc.thePlayer.posX;stallY=mc.thePlayer.posY;stallZ=mc.thePlayer.posZ;}
            else if(++stillTicks>=800&&stalled()){finish(session()>0?"paused":"failed","stalled_no_progress_near_"+(int)Math.floor(stallX)+","+(int)Math.floor(stallY)+","+(int)Math.floor(stallZ));return;}
            if(mc.thePlayer.getHealth()<health||mc.thePlayer.isBurning()||mc.thePlayer.getAir()<120){finish("failed","damage_fire_or_low_air");return;}
            for(int transitions=0;transitions<8&&!done();transitions++) {
                String oldPhase=phase();step();
                if(oldPhase.equals(phase()))return;
            }
        }catch(Exception|LinkageError error){finish("failed",error.getClass().getSimpleName()+": "+error.getMessage());}
    }
    abstract void step();
    abstract String phase();
    /** What the job has produced so far (blocks gained or placed): the receipt's rate and what makes a deadline a pause. */
    int progress(){return 0;}
    /** Progress in this session only (since start or the last resume): what the verdict and the rate are about. */
    final int session(){return progress()-sessionStart;}
    /** Whether standing still without progress is a stall for this job; a builder waiting on the model is not. */
    boolean stalled(){return true;}
    void releaseProcess() {}
    final void finish(String terminal,String why) {
        if(done())return;state=terminal;reason=why;
        try{releaseProcess();}finally{if(lease!=null)lease.close();}
        journal.progress.put("lastTicks",ticks);
        try{journal.save(status());}catch(Exception error){state="failed";reason+="; checkpoint_failed: "+error.getMessage();}
    }
    @Override public void cancel(String reason){finish("cancelled",reason);}
    @Override public boolean done(){return Set.of("succeeded","failed","cancelled","paused").contains(state);}
    @Override public boolean succeeded(){return state.equals("succeeded");}
    @Override public Map<String,Object> status() {
        Map<String,Object> out=new LinkedHashMap<>();out.put("available",true);out.put("action",journal.kind);out.put("jobId",journal.id);out.put("state",state);out.put("reason",reason);out.put("ticks",ticks);out.put("remainingTicks",remaining);
        out.put("progress",session());out.put("blocksPerMinute",ticks<20?null:Math.round(session()*1200.0/ticks*10)/10.0);
        out.put("overrideProtection",override);out.put("scope",journal.scope);out.put("controlOwned",lease!=null&&lease.isActive());out.put("serverAcknowledged",false);return out;
    }
}
