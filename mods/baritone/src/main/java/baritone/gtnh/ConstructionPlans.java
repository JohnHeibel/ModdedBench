// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh;

import dev.modbench.api.ControlRegistry;
import static baritone.gtnh.pathing.WorkSpec.*;
import baritone.gtnh.pathing.WorkSpec;
import net.minecraft.client.Minecraft;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Sequenced, durable schematic upload. Immutable finished plans are world scoped. */
final class ConstructionPlans {
    private static Path root(){return Minecraft.getMinecraft().mcDataDir.toPath().resolve("modbench/plans");}
    private static Path path(String id,String suffix){UUID.fromString(id);return root().resolve(id+suffix);}
    static Map<String,Object> stage(Map<String,Object> params) {
        try {
            String operation=string(params,"operation","");
            if(operation.equals("begin")) {
                Map<String,Object> spec=child(params,"spec");if(spec.containsKey("cells")||spec.containsKey("selection")||spec.containsKey("planId"))throw new IllegalArgumentException("stage spec must omit cells/selection/planId");
                String id=UUID.randomUUID().toString();Map<String,Object> manifest=new LinkedHashMap<>();manifest.put("scope",ControlRegistry.memory().memory().scope());manifest.put("spec",spec);manifest.put("count",0);manifest.put("parts",List.of());
                write(path(id,".stage.json"),manifest);return Map.of("stageId",id,"count",0);
            }
            String id=string(params,"stageId","");Map<String,Object> manifest=read(path(id,".stage.json"));checkScope(manifest);
            int count=integer(manifest,"count",0,0,1048576);List<String> parts=new ArrayList<>();for(Object part:list(manifest.get("parts")))parts.add((String)part);
            if(operation.equals("append")) {
                if(Files.exists(path(id,".plan.json")))throw new IllegalArgumentException("plan already finalized");
                int offset=integer(params,"offset",-1,0,1048576);List<?> entries=list(params.get("cells"));if(entries.isEmpty()||entries.size()>4096||offset+entries.size()>1048576)throw new IllegalArgumentException("append needs 1..4096 cells, total <=1048576");
                if(offset<count) {
                    int prior=0;for(String name:parts) {
                        var stored=list(WorkJournal.JSON.fromJson(Files.readString(root().resolve(name),StandardCharsets.UTF_8),List.class));
                        if(prior==offset&&WorkJournal.JSON.toJsonTree(stored).equals(WorkJournal.JSON.toJsonTree(entries)))return Map.of("stageId",id,"count",count,"replayed",true);
                        prior+=stored.size();
                    }
                    throw new IllegalArgumentException("append retry differs from committed cells");
                }
                if(offset!=count)throw new IllegalArgumentException("stage offset mismatch; expected "+count);
                Map<String,Object> batch=new LinkedHashMap<>(child(manifest,"spec"));batch.put("cells",entries);WorkSpec.cells(batch);
                String part=id+"."+parts.size()+".cells.json";write(root().resolve(part),entries);parts.add(part);manifest.put("parts",parts);manifest.put("count",count+entries.size());write(path(id,".stage.json"),manifest);
                return Map.of("stageId",id,"count",count+entries.size());
            }
            if(operation.equals("finish")) {
                Map<String,Object> spec=assemble(manifest);WorkSpec.cells(spec);write(path(id,".plan.json"),manifest);return Map.of("stageId",id,"planId",id,"count",count);
            }
            if(operation.equals("status"))return Map.of("stageId",id,"count",count,"finished",Files.exists(path(id,".plan.json")));
            throw new IllegalArgumentException("unknown stage operation");
        }catch(java.io.IOException error){throw new IllegalArgumentException("schematic storage: "+error.getMessage(),error);}
    }
    static Map<String,Object> resolve(Map<String,Object> params) {
        if(!params.containsKey("planId"))return params;
        if(!Set.of("planId","timeoutTicks","overrideProtection","allowBreak","allowPlace","_timeout_ms").containsAll(params.keySet()))throw new IllegalArgumentException("finished plan accepts only timeout and edit permission overrides");
        try {var manifest=read(path(string(params,"planId",""),".plan.json"));checkScope(manifest);var spec=assemble(manifest);for(var e:params.entrySet())if(!e.getKey().equals("planId"))spec.put(e.getKey(),e.getValue());return spec;}
        catch(java.io.IOException error){throw new IllegalArgumentException("plan unavailable",error);}
    }
    private static Map<String,Object> assemble(Map<String,Object> manifest) throws java.io.IOException {
        Map<String,Object> spec=new LinkedHashMap<>(child(manifest,"spec"));List<Object> cells=new ArrayList<>();
        for(Object row:list(manifest.get("parts"))){String name=(String)row;if(!name.matches("[0-9a-f-]{36}\\.\\d+\\.cells\\.json"))throw new IllegalArgumentException("invalid schematic part");Path file=root().resolve(name);if(Files.size(file)>8*1024*1024)throw new IllegalArgumentException("schematic part too large");cells.addAll(list(WorkJournal.JSON.fromJson(Files.readString(file,StandardCharsets.UTF_8),List.class)));}
        if(cells.size()!=integer(manifest,"count",0,1,1048576))throw new IllegalArgumentException("incomplete staged plan");spec.put("cells",cells);return spec;
    }
    private static void checkScope(Map<String,Object> manifest){if(!Objects.equals(manifest.get("scope"),ControlRegistry.memory().memory().scope()))throw new IllegalArgumentException("plan belongs to another world/dimension");}
    private static Map<String,Object> read(Path file) throws java.io.IOException {if(Files.size(file)>8*1024*1024)throw new IllegalArgumentException("manifest too large");return object(WorkJournal.JSON.fromJson(Files.readString(file,StandardCharsets.UTF_8),Map.class));}
    private static void write(Path file,Object data) throws java.io.IOException {
        Files.createDirectories(file.getParent());Path temporary=file.resolveSibling(file.getFileName()+".tmp");byte[] bytes=WorkJournal.JSON.toJson(data).getBytes(StandardCharsets.UTF_8);if(bytes.length>8*1024*1024)throw new IllegalArgumentException("schematic part too large");
        try(var channel=java.nio.channels.FileChannel.open(temporary,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)){var buffer=java.nio.ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);}
        try{Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING);}
    }
}
