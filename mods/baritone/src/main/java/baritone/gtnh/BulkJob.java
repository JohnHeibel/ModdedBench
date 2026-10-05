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
    final Stall stall;
    int remaining,ticks,progressSeen,sessionStart;
    final Symptoms symptoms=new Symptoms();
    String state="preparing",reason="";
    InputArbiter.Lease lease;
    /** Open while a job that has ended puts back what it took, under its own controls; see closing(). */
    final Ending ending=new Ending();
    BulkJob(BaritoneNavigation navigation,WorkJournal journal,Map<String,Object> options) {
        this.navigation=navigation;this.journal=journal;params=new LinkedHashMap<>(journal.spec);params.putAll(options);
        override=bool(options,"overrideProtection",false);allowBreak=bool(params,"allowBreak",false);allowPlace=bool(params,"allowPlace",false);
        remaining=integer(params,"timeoutTicks",12000,1,72000);stall=WorkAccess.stall(params);
    }
    void begin() {
        lease=ControlRegistry.controls().arbiter().acquire("baritone_"+journal.kind,this::revoked,override,true);
        sessionStart=progress();
        journal.save(status());journal.prune();
    }
    final void tick() {
        if(done())return;
        try {
            // Death first: a respawn replaces the player, and that is still a death, not a changed world.
            if(WorkAccess.died(player)){finish("failed","player_died");return;}
            if(mc.theWorld!=world||mc.thePlayer!=player||!journal.scope.equals(ControlRegistry.memory().memory().scope())){cancel("world_or_player_changed");return;}
            // A screen takes the lease away as it opens: the job that opened it answers for the screen first.
            if(mc.currentScreen!=null&&!ControlRegistry.controls().ownsPlayerInventory(lease)){guiOpened();return;}
            if(!lease.isActive()){cancel(WorkAccess.lost(lease,"superseded"));return;}
            // The deadline is a budget, not a verdict: a job that has produced something stops as paused, with its rate in the
            // receipt, and mb_work_resume continues it. A session that produced nothing has failed, whatever earlier ones did,
            // so resuming a stuck job cannot come back paused for ever.
            if(--remaining<=0){finish(session()>0?"paused":"failed",session()>0?"timeout_with_progress":"timeout_without_progress");return;}ticks++;
            // The upstream engine re-plans for ever around a target it cannot reach (bobbing in a pond, pacing a ledge): the
            // shared watchdog ends that with where it happened, judged the same way as the deadline.
            progressSeen=progress();
            if(stall.tick(activity(),(int)Math.floor(mc.thePlayer.posX),(int)Math.floor(mc.thePlayer.boundingBox.minY+.001),(int)Math.floor(mc.thePlayer.posZ),excused())){finish(session()>0?"paused":"failed",stall.reason());return;}
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
    /** What the stall watchdog counts as work besides new ground: progress, and whatever else this job changes on its way. */
    long activity(){return progress();}
    /** The ticks the watchdog excuses, once per watch, while the job waits on something with an end of its own: a path search in flight. */
    int excused(){return WorkAccess.searchBudget(navigation.reference());}
    void releaseProcess() {}
    /** The controls were taken away. For a screen this job's own click opened, the job answers in its next tick: it closes the screen and says which click. */
    private void revoked(String reason){if(reason.endsWith("gui_open")&&awaitsScreen())return;cancel(reason);}
    /** A screen this job does not own is open. */
    void guiOpened(){cancel("gui_opened");}
    /** Whether a screen opening now would be this job's own doing. */
    boolean awaitsScreen(){return false;}
    /**
     * The job is ending by itself (it finished, stopped, ran out, or was paused) and still has its controls. True when
     * it has something to put back first: it then runs on in state closing, step() does the work and calls closed(),
     * and the job ends as it was ending. A cancel never closes: whoever cancels takes the controls.
     */
    boolean closing(String terminal,String why){return false;}
    final boolean closing(){return ending.open()&&!done();}
    final void closed(){finish("",Ending.DONE,true);}
    /** The reason a job ends for, as that kind of job says it: the shared deadline and watchdog have one wording for all. */
    String named(String why){return why;}
    final void finish(String terminal,String why){finish(terminal,why,false);}
    private void finish(String terminal,String why,boolean hard) {
        if(done())return;
        String named=ending.open()?why:named(why);
        if(ending.defer(terminal,named,()->!hard&&lease!=null&&lease.isActive()&&closing(terminal,why))){state="closing";reason=named;journal.save(status());return;}
        state=ending.terminal(terminal);reason=ending.reason(named);
        try{releaseProcess();}finally{if(lease!=null)lease.close();}
        journal.progress.put("lastTicks",ticks);
        try{journal.save(status());}catch(Exception error){state="failed";reason+="; checkpoint_failed: "+error.getMessage();}
    }
    @Override public void cancel(String reason){finish("cancelled",reason,true);}
    @Override public boolean done(){return Set.of("succeeded","failed","cancelled","paused").contains(state);}
    @Override public boolean succeeded(){return state.equals("succeeded");}
    @Override public Map<String,Object> status() {
        Map<String,Object> out=new LinkedHashMap<>();out.put("available",true);out.put("action",journal.kind);out.put("jobId",journal.id);out.put("state",state);out.put("reason",reason);out.put("ticks",ticks);out.put("remainingTicks",remaining);out.put("stall",stall.status());
        out.put("progress",session());out.put("blocksPerMinute",ticks<20?null:Math.round(session()*1200.0/ticks*10)/10.0);
        out.put("overrideProtection",override);out.put("scope",journal.scope);out.put("controlOwned",lease!=null&&lease.isActive());out.put("serverAcknowledged",false);out.put("symptoms",symptoms.summary());
        if(!ending.cut().isEmpty())out.put("closingCut",ending.cut());
        return out;
    }
}
