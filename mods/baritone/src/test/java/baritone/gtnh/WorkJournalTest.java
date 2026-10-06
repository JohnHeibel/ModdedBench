// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package baritone.gtnh;

import baritone.ForgePlanningTestRunner;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import org.junit.*;
import static org.junit.Assert.*;

@org.junit.runner.RunWith(ForgePlanningTestRunner.class)
public class WorkJournalTest {
    private Path directory;
    private long clock=1_700_000_000_000L;
    @Before public void temporary()throws Exception{directory=Files.createTempDirectory("work-journal-test");}
    @After public void cleanup()throws Exception{
        try(var paths=Files.walk(directory)){paths.sorted(Comparator.reverseOrder()).forEach(p->p.toFile().delete());}
    }
    /** A journal as save() leaves it, a second newer than the one before. */
    private String journal(String kind,String state)throws Exception{
        String id=UUID.randomUUID().toString();
        Files.writeString(directory.resolve(id+".spec.json"),"{}");Files.writeString(directory.resolve(id+".attempts.jsonl"),"");
        Path file=directory.resolve(id+".json");
        Files.writeString(file,"{\"version\":2,\"jobId\":\""+id+"\",\"kind\":\""+kind+"\",\"progress\":{},\"receipt\":{\"state\":\""+state+"\"}}");
        Files.setLastModifiedTime(file,FileTime.fromMillis(clock+=1000));
        return id;
    }
    private Set<String> left()throws Exception{
        try(var files=Files.list(directory)){return new TreeSet<>(files.map(f->f.getFileName().toString()).toList());}
    }
    @Test public void aJobBeginningLeavesTheNewestTwentyThatEndedAndTheNewestTwentyPausedOfEachKind()throws Exception{
        String current=journal("mine","succeeded");             // the oldest file of all: a job being resumed
        List<String> builds=new ArrayList<>(),ended=new ArrayList<>(),paused=new ArrayList<>();
        for(int i=0;i<2;i++)builds.add(journal("build","paused"));
        for(int i=0;i<25;i++){ended.add(journal(i%2==0?"mine":"build",List.of("succeeded","failed","cancelled").get(i%3)));if(i<23)paused.add(journal("mine","paused"));}
        Files.writeString(directory.resolve("notes.json"),"{}");  // not a journal
        WorkJournal.prune(directory,current);
        Set<String> expected=new TreeSet<>(List.of("notes.json"));
        List<String> kept=new ArrayList<>(List.of(current));kept.addAll(builds);
        kept.addAll(ended.subList(25-WorkJournal.KEEP,25));kept.addAll(paused.subList(23-WorkJournal.KEEP,23));
        for(String id:kept)for(String suffix:List.of(".json",".spec.json",".attempts.jsonl"))expected.add(id+suffix);
        assertEquals(expected,left());
        // A journal that cannot be read counts as one that ended: it is the newest, so the oldest of those goes instead.
        String unreadable=journal("mine","failed");Files.writeString(directory.resolve(unreadable+".json"),"not json");
        WorkJournal.prune(directory,current);
        for(String suffix:List.of(".json",".spec.json",".attempts.jsonl")){expected.add(unreadable+suffix);expected.remove(ended.get(25-WorkJournal.KEEP)+suffix);}
        assertEquals(expected,left());
        // No directory yet (the first job of a world) is nothing to do.
        WorkJournal.prune(directory.resolve("absent"),current);
    }

    private WorkJournal fresh(){String id=UUID.randomUUID().toString();return new WorkJournal(id,"build","test",new LinkedHashMap<>(Map.of("name","spec")),directory.resolve("work").resolve(id+".json"));}
    private static Map<String,Object> receipt(Object state,int n){Map<String,Object> out=new LinkedHashMap<>();out.put("state",state);out.put("n",n);return out;}
    private static double n(Path file)throws Exception{return (Double)((Map<?,?>)WorkJournal.JSON.fromJson(Files.readString(file),Map.class).get("receipt")).get("n");}
    /** The writer held on a task of the test's: what save() does by itself is all that happens until `release`. */
    private static java.util.concurrent.CountDownLatch hold()throws Exception{
        var held=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        WorkJournal.WRITER.execute(()->{held.countDown();try{release.await();}catch(InterruptedException e){}});held.await();return release;
    }
    @Test public void aSaveTouchesNoDiskAndTheLatestCheckpointIsTheOneWritten()throws Exception{
        WorkJournal journal=fresh();Path file=directory.resolve("work").resolve(journal.id+".json");
        var release=hold();int before=WorkJournal.written;
        for(int i=1;i<=5;i++){journal.recordAttempt("cell",i);journal.recordClick(Map.of("click",i));journal.progress.put("at",i);journal.save(receipt("running",i));}
        // The checkpoint is bytes when save returns: what the game changes afterwards is not in it.
        journal.progress.put("at","later");
        assertFalse("save made no directory, wrote no file",Files.exists(directory.resolve("work")));
        release.countDown();WorkJournal.flush();
        assertEquals("five saves behind a busy writer are one write",before+1,WorkJournal.written);
        assertEquals(5.0,n(file),0);assertTrue(Files.readString(file).contains("\"at\":5"));
        assertEquals("{\"name\":\"spec\"}",Files.readString(file.resolveSibling(journal.id+".spec.json")));
        // Rows are joined, never dropped, in the order recorded.
        assertEquals(List.of(1,2,3,4,5),Files.readAllLines(file.resolveSibling(journal.id+".attempts.jsonl")).stream().map(l->((Double)WorkJournal.JSON.fromJson(l,Map.class).get("count")).intValue()).toList());
        assertEquals(List.of(1,2,3,4,5),Files.readAllLines(file.resolveSibling(journal.id+".clicks.jsonl")).stream().map(l->((Double)WorkJournal.JSON.fromJson(l,Map.class).get("click")).intValue()).toList());
        try(var files=Files.list(file.getParent())){assertTrue("no temporary file left",files.noneMatch(f->f.toString().endsWith(".tmp")));}
    }
    @Test public void savesAreWrittenInOrderAndAJobsLastOneIsOnDiskWhenCloseReturns()throws Exception{
        WorkJournal journal=fresh();Path file=directory.resolve("work").resolve(journal.id+".json");
        for(int i=1;i<=50;i++){journal.recordAttempt("cell",i);journal.save(receipt("running",i));}
        // No flush by the test: close is the flush. A reader that follows sees the end, with every row before it.
        journal.recordAttempt("cell",51);journal.close(receipt("paused",51));
        assertEquals(51.0,n(file),0);assertEquals(51,Files.readAllLines(file.resolveSibling(journal.id+".attempts.jsonl")).size());
        // Another journal's writes queue behind this one's: one writer, one order.
        WorkJournal other=fresh();var release=hold();journal.save(receipt("running",52));other.save(receipt("running",1));release.countDown();WorkJournal.flush();
        assertEquals(52.0,n(file),0);assertEquals(1.0,n(directory.resolve("work").resolve(other.id+".json")),0);
    }
    @Test public void aWriteThatFailedIsThrownByTheNextSaveAndByClose()throws Exception{
        Files.writeString(directory.resolve("work"),"a file where the directory belongs");
        WorkJournal journal=fresh();journal.save(receipt("running",1));WorkJournal.flush();
        try{journal.save(receipt("running",2));fail("the failed write was not reported");}catch(IllegalStateException expected){assertTrue(expected.getMessage().startsWith("cannot checkpoint work"));}
        try{journal.close(receipt("failed",3));fail("the failed close was not reported");}catch(IllegalStateException expected){}
    }
    /** What save costs the game thread now: the checkpoint as bytes. 2,000 clicks is far past a build's click cap of 256. */
    @Test public void aSaveOfALongClickLogIsFarInsideATick()throws Exception{
        WorkJournal journal=fresh();Map<String,Object> clicks=new LinkedHashMap<>();for(int i=0;i<2000;i++)clicks.put(i+",64,"+i,"done");journal.progress.put("clicks",clicks);
        int[] n={0};baritone.TickBudget.check("journal checkpoint handed to the writer, 2000 click results",baritone.TickBudget.medianMs(()->()->journal.save(receipt("running",n[0]++))));
        journal.close(receipt("succeeded",0));
    }
}
