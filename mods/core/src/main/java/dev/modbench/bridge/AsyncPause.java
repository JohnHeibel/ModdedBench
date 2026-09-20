// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.bridge;

/**
 * Admission barrier for complete background jobs. The simulation thread never waits here, but GregTech's
 * tick lock does wait on every admitted task from inside the tick, so {@link #begin()} is only safe while
 * the tick gate is closed ({@link PauseCoordinator} enforces this).
 */
public final class AsyncPause implements PauseCoordinator.Barrier {
    public static final AsyncPause GREGTECH = new AsyncPause();
    private boolean paused;
    private int active, waiting;
    private long completed;

    public void run(Runnable work) {
        synchronized(this) {
            waiting++;
            try {
                while(paused) wait();
                active++;
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt();throw new IllegalStateException("background pause interrupted",e);
            } finally { waiting--; }
        }
        try { work.run(); }
        finally { synchronized(this) { active--;completed++; } }
    }
    @Override public synchronized void begin() { paused=true; }
    @Override public synchronized boolean requested() { return paused; }
    @Override public synchronized boolean ready() { return paused && active==0; }
    @Override public synchronized void resume() { paused=false;notifyAll(); }
    @Override public synchronized Object status() {
        return Json.object("requested",paused,"ready",ready(),"active",active,"waiting",waiting,"completed",completed);
    }
}
