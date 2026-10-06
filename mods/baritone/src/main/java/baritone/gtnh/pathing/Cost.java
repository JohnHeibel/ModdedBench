// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh.pathing;

import java.util.LinkedHashMap;
import java.util.Map;

/** What Baritone costs the game while a job runs: its game-thread time per client tick, and each search's time on its
 *  own thread. A job resets it when it starts and shows it in its status. Ticks are timed on the game thread. */
public final class Cost {
    private Cost(){}
    private static long tickNs,maxNs,totalNs,ticks;
    private static long searches,searchMsLast,searchMsMax;
    private static long pausedNs,pausedMaxNs,pausedFrames;
    /** Over these a job's status says so (overBudget): one tick, and the mean of a job that ran at least MEAN_TICKS. */
    public static final long TICK_LIMIT_MS=100,MEAN_LIMIT_MS=5,MEAN_TICKS=40;

    public static synchronized void reset(){tickNs=maxNs=totalNs=ticks=0;searches=searchMsLast=searchMsMax=0;pausedNs=pausedMaxNs=pausedFrames=0;}
    /** Game thread, world paused: one frame's servicing and planning (BaritoneNavigation.whilePaused), no tick. */
    public static synchronized void paused(long ns){pausedFrames++;pausedNs+=ns;pausedMaxNs=Math.max(pausedMaxNs,ns);}
    /** Game thread: time spent in Baritone during this tick, added up over its phases. */
    public static synchronized void add(long ns){tickNs+=ns;}
    /** Game thread: the tick is over. */
    public static synchronized void endTick(){
        maxNs=Math.max(maxNs,tickNs);totalNs+=tickNs;ticks++;tickNs=0;
    }
    /** Search thread: one plan took this long. */
    public static synchronized void searched(long ms){searches++;searchMsLast=ms;searchMsMax=Math.max(searchMsMax,ms);}
    public static synchronized Map<String,Object> status(){
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("tickNsMax",maxNs);out.put("tickNsMean",ticks==0?0:totalNs/ticks);out.put("ticks",ticks);
        out.put("pausedFrames",pausedFrames);out.put("pausedNsMax",pausedMaxNs);out.put("pausedNsMean",pausedFrames==0?0:pausedNs/pausedFrames);
        out.put("searches",searches);out.put("searchMsLast",searchMsLast);out.put("searchMsMax",searchMsMax);
        Map<String,Object> over=new LinkedHashMap<>();
        if(maxNs>TICK_LIMIT_MS*1_000_000)over.put("tickMsMax",ms(maxNs));
        if(ticks>=MEAN_TICKS&&totalNs>MEAN_LIMIT_MS*1_000_000*ticks)over.put("tickMsMean",ms(totalNs/ticks));
        if(!over.isEmpty())out.put("overBudget",over);
        return out;
    }
    private static double ms(long ns){return Math.round(ns/1e5)/10.0;}
}
