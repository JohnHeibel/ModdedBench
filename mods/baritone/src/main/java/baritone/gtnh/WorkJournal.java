// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh;

import dev.modbench.api.ControlRegistry;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.client.Minecraft;
import static baritone.gtnh.pathing.WorkSpec.*;

/**
 * Durable intent and receipts. Resume always re-observes the world before acting.
 * A checkpoint is turned into bytes on the thread that saves (the game's) and written and synced by one writer thread,
 * in the order saved. A save that finds an unwritten one of its journal replaces it (the rows to append are joined), so
 * a crash loses at most what the last save handed over. flush() returns when everything saved is on disk: a job's end,
 * every read of a journal and the game's shutdown wait for it.
 */
final class WorkJournal {
    static final Gson JSON=new Gson();
    final String id,kind,scope;
    final Map<String,Object> spec;
    final Map<String,Object> progress=new LinkedHashMap<>();
    private final Path file;
    private final StringBuilder ledger=new StringBuilder(),clicks=new StringBuilder();
    /** What one save hands the writer: bytes and text, nothing the game still changes. spec only until it is on disk. */
    private record Batch(byte[] spec,String ledger,String clicks,byte[] checkpoint){}
    static final ExecutorService WRITER=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"modbench-journal");t.setDaemon(true);return t;});
    static{Runtime.getRuntime().addShutdownHook(new Thread(WorkJournal::flush,"modbench-journal-flush"));}
    /** Checkpoint files written, counted by the writer. */
    static volatile int written;
    private Batch waiting;
    private boolean specSaved;
    private volatile Exception failure;
    WorkJournal(String kind,Map<String,Object> params) {
        this.id=UUID.randomUUID().toString();this.kind=kind;scope=ControlRegistry.memory().memory().scope();
        spec=object(JSON.fromJson(JSON.toJson(params),Map.class));file=path(id);
    }
    WorkJournal(String id,String kind,String scope,Map<String,Object> spec,Path file){this.id=id;this.kind=kind;this.scope=scope;this.spec=spec;this.file=file;}
    WorkJournal(String id) {
        Map<String,Object> data=load(id);this.id=id;kind=string(data,"kind","");scope=string(data,"scope","");spec=child(data,"spec");progress.putAll(child(data,"progress"));file=path(id);
        specSaved=Files.exists(file.resolveSibling(id+".spec.json"));
        if(!scope.equals(ControlRegistry.memory().memory().scope()))throw new IllegalArgumentException("work belongs to another world/dimension");
    }
    private static Path path(String id){UUID.fromString(id);return Minecraft.getMinecraft().mcDataDir.toPath().resolve("modbench/work").resolve(id+".json");}
    private static Map<String,Object> checkpoint(Path file) throws java.io.IOException {
        if(Files.size(file)>64*1024*1024)throw new IllegalArgumentException("work journal too large");
        return object(JSON.fromJson(Files.readString(file,StandardCharsets.UTF_8),Map.class));
    }
    static final int KEEP=20;
    // How each journal last ended, read once: only this client writes them. "paused <kind>", or "" for any other end.
    private static final Map<String,String> ended=new HashMap<>();
    private static String ended(String kind,Object state){return "paused".equals(state)?"paused "+kind:"";}
    /**
     * Every job leaves a journal. When one begins, the journals beyond the newest KEEP go, with their spec and ledger;
     * paused jobs are counted apart, per kind, since a paused job is what a resume is offered for and the tools remember
     * as many of those. At most 64 journals are read in one call, so a backlog drains over the jobs that follow.
     */
    static void prune(Path directory,String current){
        try(var listed=Files.list(directory)){
            var journals=listed.filter(f->f.getFileName().toString().matches("[0-9a-f-]{36}\\.json")).map(f->Map.entry(f,f.toFile().lastModified()))
                .sorted((a,b)->Long.compare(b.getValue(),a.getValue())).toList();
            Map<String,Integer> kept=new HashMap<>();int read=0;
            for(var journal:journals){
                String id=journal.getKey().getFileName().toString().substring(0,36);
                if(id.equals(current))continue;
                String how=ended.get(id);
                if(how==null){
                    if(++read>64)break;
                    try{var data=checkpoint(journal.getKey());how=ended(String.valueOf(data.get("kind")),child(data,"receipt").get("state"));}catch(Exception unreadable){how="";}
                    ended.put(id,how);
                }
                if(kept.merge(how,1,Integer::sum)<=KEEP)continue;
                for(String suffix:List.of(".spec.json",".attempts.jsonl",".json"))Files.deleteIfExists(directory.resolve(id+suffix));
                ended.remove(id);
            }
        }catch(java.io.IOException|RuntimeException clutter){} // a journal left behind costs disk, never the job that is starting
    }
    void prune(){prune(file.getParent(),id);}
    /** Inspection never rehydrates a million-cell spec or its per-click ledger. */
    static Map<String,Object> status(String id) {
        try {
            flush();var data=checkpoint(path(id));
            if(data.containsKey("spec"))data.put("specSummary",summary(object(data.remove("spec"))));
            else if(!data.containsKey("specSummary"))data.put("specSummary",Map.of("storedSeparately",true));
            data.put("inspection","bounded checkpoint; resume loads the complete frozen specification");
            return object(compact(data));
        }catch(Exception error){throw new IllegalArgumentException("work journal unavailable: "+error.getMessage(),error);}
    }
    private static Object compact(Object value) {
        if(value instanceof List<?> list){if(list.size()>128)return Map.of("omitted",true,"count",list.size());return list.stream().map(WorkJournal::compact).toList();}
        if(value instanceof Map<?,?> map){if(map.size()>128)return Map.of("omitted",true,"count",map.size());Map<String,Object> out=new LinkedHashMap<>();map.forEach((k,v)->out.put(k.toString(),compact(v)));return out;}
        return value;
    }
    private static Map<String,Object> summary(Map<String,Object> spec) {
        Map<String,Object> out=new LinkedHashMap<>();for(String key:List.of("name","origin","size","selection","bounds","quantity","replaceExisting","allowBreak","allowPlace"))if(spec.containsKey(key))out.put(key,compact(spec.get(key)));
        if(spec.get("cells") instanceof List<?> cells)out.put("cellCount",cells.size());
        if(spec.get("uses") instanceof List<?> uses)out.put("useCount",uses.size());return out;
    }
    static Map<String,Object> load(String id) {
        try {
            flush();Path file=path(id);var data=checkpoint(file);
            if(!data.containsKey("spec")){Path spec=file.resolveSibling(id+".spec.json");if(Files.size(spec)>256L*1024*1024)throw new IllegalArgumentException("work spec too large");data.put("spec",object(JSON.fromJson(Files.readString(spec,StandardCharsets.UTF_8),Map.class)));}
            return data;
        }
        catch(Exception error){throw new IllegalArgumentException("work journal unavailable: "+error.getMessage(),error);}
    }
    /** Everything saved so far, by any journal, is on disk or has failed when this returns. */
    static void flush(){
        try{WRITER.submit(()->{}).get();}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("interrupted while writing work journals");}
        catch(ExecutionException impossible){throw new IllegalStateException(impossible.getCause());}
    }
    /** A write that failed since the last save says so here: in the next save, and in the close that ends the job. */
    private void failed(){Exception error=failure;if(error!=null){failure=null;throw new IllegalStateException("cannot checkpoint work: "+error.getMessage(),error);}}
    /** The save that ends a job: on disk when this returns, or thrown. */
    void close(Map<String,Object> receipt){save(receipt);flush();failed();}
    /** No disk here: the checkpoint as bytes, handed to the writer. */
    void save(Map<String,Object> receipt) {
        failed();
        Map<String,Object> data=new LinkedHashMap<>();data.put("version",2);data.put("jobId",id);data.put("kind",kind);data.put("scope",scope);data.put("specSummary",summary(spec));data.put("progress",progress);data.put("receipt",receipt);
        byte[] bytes=JSON.toJson(data).getBytes(StandardCharsets.UTF_8),first=specSaved?null:JSON.toJson(spec).getBytes(StandardCharsets.UTF_8);specSaved=true;
        synchronized(this){
            Batch before=waiting;
            waiting=before==null?new Batch(first,ledger.toString(),clicks.toString(),bytes):new Batch(before.spec,before.ledger+ledger,before.clicks+clicks,bytes);
            if(before==null)WRITER.execute(this::write);
        }
        ledger.setLength(0);clicks.setLength(0);ended.put(id,ended(kind,receipt.get("state")));
    }
    private void write(){
        Batch batch;synchronized(this){batch=waiting;waiting=null;}
        try {
            Files.createDirectories(file.getParent());
            if(batch.spec!=null)write(file.resolveSibling(id+".spec.json"),batch.spec,256L*1024*1024);
            if(!batch.ledger.isEmpty())Files.writeString(file.resolveSibling(id+".attempts.jsonl"),batch.ledger,StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
            if(!batch.clicks.isEmpty())Files.writeString(file.resolveSibling(id+".clicks.jsonl"),batch.clicks,StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
            write(file,batch.checkpoint,64L*1024*1024);written++;
        }catch(Exception error){failure=error;}
    }
    /**
     * One row per click the game took, in order: <id>.attempts.jsonl, a measurement log (harness/smoke/build_order.py),
     * never read back. Rows wait here and are appended with the next checkpoint.
     */
    void recordAttempt(String key,int count){ledger.append(JSON.toJson(Map.of("key",key,"count",count))).append('\n');}
    /** One row per click of a build's click cells and uses, in full: <id>.clicks.jsonl, appended like the attempts. The receipt keeps counts. */
    void recordClick(Map<String,Object> row){clicks.append(JSON.toJson(row)).append('\n');}
    private static void write(Path file,byte[] bytes,long limit) throws java.io.IOException {
        if(bytes.length>limit)throw new IllegalArgumentException("work file exceeds size limit");Path temp=file.resolveSibling(file.getFileName()+".tmp");
        try(var channel=java.nio.channels.FileChannel.open(temp,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)){var buffer=java.nio.ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);}
        try{Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException unsupported){Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING);}
    }
}
