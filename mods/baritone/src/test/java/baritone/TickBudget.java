// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package baritone;

import java.util.Arrays;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * One game tick's work against a limit, without a game. The game has 50 ms a tick for everything, and what runs on its
 * thread for a job is sliced to between a quarter of a millisecond and two. LIMIT_MS is loose on purpose: four times the
 * slowest tick measured when it was set (TickBudgetTest), so a busy machine passes, and a tick that reads the world
 * unsliced (tens to hundreds of ms) fails. A test hands `fresh`, which sets one tick up (not timed) and returns it; what
 * is measured is printed, so the margin shows in the test report. A path found over the limit is not hidden by raising
 * it: it is given its own limit there, named as a finding.
 */
public final class TickBudget {
    private TickBudget(){}
    public static final double LIMIT_MS=20;
    private static final int WARM=12,RUNS=9;
    /** The median of RUNS ticks, each set up afresh, after WARM untimed ones (the JIT's). */
    public static double medianMs(Supplier<Runnable> fresh){
        long[] ns=new long[RUNS];
        for(int i=-WARM;i<RUNS;i++){Runnable tick=fresh.get();long t=System.nanoTime();tick.run();if(i>=0)ns[i]=System.nanoTime()-t;}
        Arrays.sort(ns);return ns[RUNS/2]/1e6;
    }
    /**
     * The worst tick of a whole run of them (a tick answers whether another follows), in the best of five runs after two
     * untimed: a tick that is slow by its own work is slow in every run, one the machine interrupted is not. On a machine
     * with every core busy five runs may all be interrupted: while the best is over limitMs, up to ten more.
     */
    public static double worstMs(double limitMs,Supplier<BooleanSupplier> fresh){
        long best=Long.MAX_VALUE;
        for(int i=-2;i<5||i<15&&best>limitMs*1e6;i++){
            BooleanSupplier tick=fresh.get();long worst=0;
            for(boolean more=true;more;){long t=System.nanoTime();more=tick.getAsBoolean();worst=Math.max(worst,System.nanoTime()-t);}
            if(i>=0)best=Math.min(best,worst);
        }
        return best/1e6;
    }
    public static double worstMs(Supplier<BooleanSupplier> fresh){return worstMs(LIMIT_MS,fresh);}
    /** Printed with no limit: a figure for the report. */
    public static double measure(String what,double ms){System.out.printf(Locale.ROOT,"tick budget: %s: %.3f ms%n",what,ms);return ms;}
    public static double check(String what,double ms){return check(what,LIMIT_MS,ms);}
    public static double check(String what,double limitMs,double ms){
        measure(what,ms);
        if(ms>limitMs)throw new AssertionError(String.format(Locale.ROOT,"%s took %.2f ms of one game tick (limit %.0f ms): slice it over ticks, or do it off the game thread",what,ms,limitMs));
        return ms;
    }
}
