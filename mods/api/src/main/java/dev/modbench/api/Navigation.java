// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.api;

import java.util.Map;

/** Optional navigation service. Independent of MCP, transport and Minecraft classes. */
public interface Navigation {
    /** Cancel queued/native API processes as well as jobs already holding controls. */
    default void cancel(String reason) {}
    Job goTo(int x, int y, int z, int timeoutTicks);
    Job mineBlock(int x, int y, int z, int timeoutTicks);
    default Job mineBlock(int x,int y,int z,int timeoutTicks,boolean autoTool) {return mineBlock(x,y,z,timeoutTicks);}
    default Job goTo(int x,int y,int z,int timeoutTicks,boolean allowBreak,boolean allowPlace) {
        if(allowBreak||allowPlace) throw new UnsupportedOperationException("terrain work is unavailable");
        return goTo(x,y,z,timeoutTicks);
    }
    default Job placeBlock(int x,int y,int z,int timeoutTicks) {throw new UnsupportedOperationException("placement unavailable");}
    default Map<String,Object> inspectTools(int x,int y,int z) {throw new UnsupportedOperationException("tool inspection unavailable");}
    default Job goTo(int x,int y,int z,int ticks,boolean allowBreak,boolean allowPlace,boolean overrideProtection) {
        if(overrideProtection) throw new UnsupportedOperationException("protection override unavailable");return goTo(x,y,z,ticks,allowBreak,allowPlace);
    }
    default Job mineBlock(int x,int y,int z,int ticks,boolean autoTool,boolean overrideProtection) {
        if(overrideProtection) throw new UnsupportedOperationException("protection override unavailable");return mineBlock(x,y,z,ticks,autoTool);
    }
    default Job placeBlock(int x,int y,int z,int ticks,boolean overrideProtection) {
        if(overrideProtection) throw new UnsupportedOperationException("protection override unavailable");return placeBlock(x,y,z,ticks);
    }
    default Job route(String name,boolean reverse,int startIndex,int timeoutTicks,boolean allowBreak,boolean allowPlace,boolean overrideProtection) {throw new UnsupportedOperationException("saved routes unavailable");}
    default Job mine(Map<String,Object> params){throw new UnsupportedOperationException("quantity mining unavailable");}
    default Job follow(Map<String,Object> params){throw new UnsupportedOperationException("entity following unavailable");}
    default Job fight(Map<String,Object> params){throw new UnsupportedOperationException("fighting unavailable");}
    default Job sourceProcess(Map<String,Object> params){throw new UnsupportedOperationException("source resource processes unavailable");}
    default Map<String,Object> cache(Map<String,Object> params){throw new UnsupportedOperationException("terrain cache unavailable");}
    default Job build(Map<String,Object> params){throw new UnsupportedOperationException("schematic building unavailable");}
    default Map<String,Object> pauseBuild(){throw new UnsupportedOperationException("builder pause unavailable");}
    default Map<String,Object> buildMaterials(){throw new UnsupportedOperationException("builder materials unavailable");}
    default Map<String,Object> stageBuild(Map<String,Object> params){throw new UnsupportedOperationException("schematic staging unavailable");}
    default Job resume(String jobId,Map<String,Object> options){throw new UnsupportedOperationException("work resume unavailable");}
    default Map<String,Object> previewBuild(Map<String,Object> params){throw new UnsupportedOperationException("build preview unavailable");}
    default Map<String,Object> scan(Map<String,Object> params){throw new UnsupportedOperationException("block scan unavailable");}
    default Map<String,Object> importSchematic(Map<String,Object> params){throw new UnsupportedOperationException("schematic import unavailable");}
    default Map<String,Object> copy(Map<String,Object> params){throw new UnsupportedOperationException("region copy unavailable");}
    default Map<String,Object> workStatus(String jobId){throw new UnsupportedOperationException("work journal unavailable");}
    Map<String, Object> inspectFluid(int x, int y, int z);
    Map<String, Object> inspectTerrain(int x, int y, int z);
    Map<String, Object> status();
    default Map<String,Object> settings(Map<String,Object> params){throw new UnsupportedOperationException("source settings unavailable");}

    interface Job {
        boolean done();
        boolean succeeded();
        void cancel(String reason);
        Map<String, Object> status();
    }
}
