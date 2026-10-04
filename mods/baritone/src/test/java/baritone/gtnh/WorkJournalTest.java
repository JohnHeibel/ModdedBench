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
}
