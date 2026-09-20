// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh;
import baritone.compat.Registry;
import baritone.Baritone;
import baritone.api.utils.BlockUtils;
import java.util.*;
import java.util.concurrent.*;
import static baritone.gtnh.pathing.WorkSpec.*;

/** Cache administration; disk operations return pollable results and never block native input. */
final class ReferenceCache {
    private record Task(String scope,CompletableFuture<Map<String,Object>> result){}
    private static final Map<String,Task> tasks=new LinkedHashMap<>();
    static Map<String,Object> call(Baritone engine,Map<String,Object> params){
        WorkAccess.player();var data=engine.getWorldProvider().getCurrentWorld();
        if(data==null)throw new IllegalArgumentException("server world identity required for cache");
        String scope=dev.modbench.api.ControlRegistry.memory().memory().scope();
        String operation=String.valueOf(params.getOrDefault("operation","status"));
        if(operation.equals("result")){
            String id=String.valueOf(params.get("id"));Task task=tasks.get(id);
            if(task==null||!task.scope.equals(scope))throw new IllegalArgumentException("unknown cache task in this world");
            if(!task.result.isDone())return Map.of("id",id,"state","running");
            try{return Map.of("id",id,"state","complete","result",task.result.join());}
            catch(CompletionException error){return Map.of("id",id,"state","failed","error",String.valueOf(error.getCause()));}
        }
        if(operation.equals("status"))return Map.of("scope",scope,"directory",data.directory.toString(),"cache",data.cache.diagnostics(),"schema","gtnh-v1-registry-metadata","terrain","source two-bit approximation; loaded world always takes precedence");
        if(operation.equals("block")){
            var p=pos(params.get("pos"));var region=data.cache.getRegion(p.getX()>>9,p.getZ()>>9);var state=region==null?null:region.getBlock(p.getX()&511,p.getY(),p.getZ()&511);
            var out=new LinkedHashMap<String,Object>();out.put("cached",state!=null);out.put("approximate",true);
            if(state!=null){out.put("id",Registry.name(state.getBlock()));out.put("meta",state.meta);}return out;
        }
        if(operation.equals("repack"))return Map.of("capturedChunks",baritone.cache.WorldScanner.INSTANCE.repack(engine.getPlayerContext(),integer(params,"range",2,0,16)),"cache",data.cache.diagnostics());
        if(!Set.of("save","reload","locations").contains(operation))throw new IllegalArgumentException("cache operation must be status, block, repack, save, reload, locations or result");
        String block=null;
        if(operation.equals("locations")){
            block=BlockUtils.blockToString(BlockUtils.stringToBlockRequired(String.valueOf(params.get("block"))));
            if(params.containsKey("meta"))block+="@"+integer(params,"meta",0,0,15);
        }
        var feet=engine.getPlayerContext().playerFeet();String requestedBlock=block;
        int maximum=integer(params,"limit",64,1,4096),distance=integer(params,"regionDistanceSquared",2,0,64);
        tasks.entrySet().removeIf(e->e.getValue().result.isDone()&&tasks.size()>=64);
        if(tasks.size()>=64)throw new IllegalStateException("too many cache operations in progress");
        String id=UUID.randomUUID().toString();
        tasks.put(id,new Task(scope,CompletableFuture.supplyAsync(()->{
            if(operation.equals("save")){data.cache.flushAndSave();return Map.<String,Object>of("cache",data.cache.diagnostics(),"capturedBeforeRequestPacked",true);}
            if(operation.equals("reload")){data.cache.reloadAllFromDisk();return Map.<String,Object>of("cache",data.cache.diagnostics());}
            var positions=data.cache.getLocationsOf(requestedBlock,maximum,feet.x,feet.z,distance);
            return Map.<String,Object>of("positions",positions.stream().limit(maximum).map(p->List.of(p.getX(),p.getY(),p.getZ())).toList(),"truncated",positions.size()>maximum,"evidence","cached observation; recheck loaded native state before acting");
        },Baritone.getExecutor())));
        return Map.of("id",id,"state","running");
    }
}
