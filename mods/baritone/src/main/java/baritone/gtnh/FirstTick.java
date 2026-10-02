// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh;

import baritone.Baritone;
import java.util.Map;
import java.util.function.BooleanSupplier;
import net.minecraft.entity.Entity;

/** Planning in a paused world for a job the custom goal drives, and how its first ticks went: whether the search started
 *  while paused, whether the plan was ready on tick 1, the tick a path was first followed and the tick whose movement
 *  first displaced the player (1 for both: it moved on the first tick the world ran). */
final class FirstTick {
    private final Baritone engine;
    private final double x,y,z;
    private boolean tried,searchStartedPaused,planReady;
    private int firstPathTick=-1,firstMovedTick=-1;
    FirstTick(Baritone engine,Entity player){this(engine,player.posX,player.posY,player.posZ);}
    FirstTick(Baritone engine,double x,double y,double z){this.engine=engine;this.x=x;this.y=y;this.z=z;}
    /** Whether the paused world still has planning to do for the job at `ticks` ticks run. */
    boolean planning(int ticks){return ticks==0&&(!tried||searchStartedPaused&&engine.getPathingBehavior().getInProgress().isPresent());}
    void plan(){plan(engine.getPathingBehavior()::planWhilePaused);}
    void plan(BooleanSupplier search){if(tried)return;tried=true;searchStartedPaused=search.getAsBoolean();}
    /** Tick `tick` (from 1), before the engine's own tick. A position read now shows the movement of the tick before. */
    void before(int tick,Entity player){before(tick,player.posX,player.posY,player.posZ);}
    void before(int tick,double px,double py,double pz){
        if(tick==1)planReady=engine.getPathingBehavior().getCurrent()!=null;
        if(firstMovedTick<0&&(px-x)*(px-x)+(py-y)*(py-y)+(pz-z)*(pz-z)>1e-4)firstMovedTick=tick-1;
    }
    /** Tick `tick`, after the engine's tick: a path in hand was followed in it. */
    void after(int tick){if(firstPathTick<0&&engine.getPathingBehavior().getCurrent()!=null)firstPathTick=tick;}
    void status(Map<String,Object> out){
        out.put("searchStartedPaused",searchStartedPaused);out.put("planReadyAtFirstTick",planReady);
        out.put("firstPathTick",firstPathTick<0?null:firstPathTick);out.put("firstMovedTick",firstMovedTick<0?null:firstMovedTick);
    }
}
