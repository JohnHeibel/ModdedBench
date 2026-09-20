// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
package dev.modbench.bridge;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.*;

/** Cooperates with OC Machine.pause(0); never suspends threads or waits on the server thread. */
public final class ComputerPause {
    private static final Map<Object,Boolean> machines=new WeakHashMap<>();
    private static final ExecutorService executor=Executors.newSingleThreadExecutor(r->{
        Thread thread=new Thread(r,"modbench-computer-pause");thread.setDaemon(true);return thread;
    });
    private static boolean requested;
    private static CompletableFuture<Void> pending=CompletableFuture.completedFuture(null);
    private static long generation;
    private ComputerPause() {}

    public static synchronized void register(Object machine) {
        machines.put(machine,Boolean.TRUE);
        if(requested) enqueue(java.util.List.of(machine));
    }
    public static synchronized void begin() {
        if(requested) return;
        requested=true;generation++;
        enqueue(new ArrayList<>(machines.keySet()));
    }
    private static void enqueue(java.util.List<Object> targets) {
        pending=pending.thenRunAsync(()->{
            for(Object machine:targets) {
                try {
                    // Native API waits for an in-flight run, preserves an existing positive
                    // pause, and resumes a zero-duration pause on the next host tile update.
                    machine.getClass().getMethod("pause",double.class).invoke(machine,0.0);
                } catch(ReflectiveOperationException e) {
                    Throwable cause=e instanceof InvocationTargetException?e.getCause():e;
                    throw new CompletionException(cause);
                }
            }
        },executor);
    }
    public static synchronized boolean ready() { return requested && pending.isDone() && !pending.isCompletedExceptionally(); }
    public static synchronized String failure() {
        if(!pending.isCompletedExceptionally()) return null;
        try { pending.join();return null; }
        catch(CompletionException e) { return String.valueOf(e.getCause()); }
    }
    public static synchronized void resume() {
        if(!ready()) throw new IllegalStateException("computer pause has not settled");
        requested=false;
        // No start/reboot: the existing OC update path resumes the state it paused.
    }
    public static synchronized boolean requested() { return requested; }
    public static synchronized Object status() {
        return Json.object("adapter","OpenComputers Machine.pause(0)","registered",machines.size(),
            "requested",requested,"ready",ready(),"generation",generation,"error",failure());
    }
    /** The static machine registry seen as a coordinator barrier. */
    public static final PauseCoordinator.Barrier BARRIER=new PauseCoordinator.Barrier() {
        @Override public void begin() { ComputerPause.begin(); }
        @Override public boolean requested() { return ComputerPause.requested(); }
        @Override public boolean ready() { return ComputerPause.ready(); }
        @Override public void resume() { ComputerPause.resume(); }
        @Override public String failure() { return ComputerPause.failure(); }
        @Override public Object status() { return ComputerPause.status(); }
    };
}
