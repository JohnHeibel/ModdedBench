// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.client;

import com.google.gson.JsonObject;
import dev.modbench.bridge.Json;
import java.util.function.BooleanSupplier;

/**
 * Plan-while-paused on the clock's side. A gated frame (paused, or a spent step) runs no client tick, so the navigation
 * provider's game-thread computation is run here instead. An action that resumes the world has its first (credit) tick
 * held, the world still paused, while its job plans: the search's time is then wall time, not game time the player spends
 * standing still, and the first tick follows the plan. Past HOLD_CAP_NS the tick runs and the job plans on in game time.
 */
final class PlanHold {
    /** Wall time, with the world paused. Above the search's own failure budget and a first scan of the largest bounds. */
    static final long HOLD_CAP_NS=10_000_000_000L;
    private long since=-1,holds,capped,lastHeldMs=-1,gatedFrames,heldFrames;
    /** A gated frame: the provider's computation only. */
    void gated(Runnable service){gatedFrames++;service.run();}
    /**
     * The credit tick is due: true holds it this frame, after one frame of the provider's computation. Released without
     * servicing, as the tick that follows services the provider itself.
     */
    boolean hold(BooleanSupplier planning,Runnable service,long now){
        if(planning.getAsBoolean()){
            if(since<0){since=now;holds++;}
            if(now-since<HOLD_CAP_NS){heldFrames++;service.run();return true;}
            capped++;
        }
        if(since>=0){lastHeldMs=(now-since)/1_000_000;since=-1;}
        return false;
    }
    /** The credit tick was dropped (the world resumed or the connection changed): the next hold starts afresh. */
    void reset(){since=-1;}
    JsonObject status(){return Json.object("holds",holds,"capped",capped,"lastHeldMs",lastHeldMs,"heldFrames",heldFrames,"gatedFrames",gatedFrames);}
}
