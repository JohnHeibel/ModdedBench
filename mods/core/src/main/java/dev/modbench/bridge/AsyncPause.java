// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
package dev.modbench.bridge;

/** Admission barrier for complete background jobs. The simulation thread never waits here. */
public final class AsyncPause {
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
    public synchronized void begin() { paused=true; }
    public synchronized boolean ready() { return paused && active==0; }
    public synchronized void resume() { paused=false;notifyAll(); }
    public synchronized Object status() {
        return Json.object("requested",paused,"ready",ready(),"active",active,"waiting",waiting,"completed",completed);
    }
}
