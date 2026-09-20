// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.api;

import java.util.Map;

/** The client's persistent world memory and its recording/protection state. */
public interface MemoryAccess {
    WorldMemory memory();
    void bind(String id);
    void disconnected();
    Map<String,Object> context();
    Map<String,Object> status();
    WorldMemory.Pos feet();
    Map<String,Object> record(String action,String name,boolean replace,double radius) throws Exception;
    void sample();
    /** Null means permitted. */
    String editProblem(int x,int y,int z,boolean override,boolean automated);
    void endTick();
    /** Called immediately before vanilla block editing; false vetoes the action. */
    boolean blockAction(int action,int x,int y,int z,int side);
}
