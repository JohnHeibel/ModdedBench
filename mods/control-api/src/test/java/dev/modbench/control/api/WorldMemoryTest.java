// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
package dev.modbench.control.api;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class WorldMemoryTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private WorldMemory.Pos p(int x,int y,int z) {return new WorldMemory.Pos(x,y,z);}
    @Test public void regionsPersistWithInclusiveBoundsAndOverlap() throws Exception {
        Path path=temp.getRoot().toPath().resolve("world.json");WorldMemory m=new WorldMemory(path,"world:0");
        m.protect(new WorldMemory.Region("base",p(-10,60,-10),p(10,90,10)),false);
        m.protect(new WorldMemory.Region("machines",p(0,65,0),p(3,70,3)),false);
        WorldMemory restored=new WorldMemory(path,"world:0");
        assertEquals(List.of("base","machines"),restored.snapshot().protectedAt(p(0,65,0)));
        assertEquals(List.of("base"),restored.snapshot().protectedAt(p(-10,90,10)));
        assertTrue(restored.snapshot().protectedAt(p(-11,90,10)).isEmpty());
    }
    @Test public void weakeningOrRemovingProtectionRequiresExplicitOverride() throws Exception {
        Path path=temp.getRoot().toPath().resolve("world.json");WorldMemory m=new WorldMemory(path,"world:0");
        m.protect(new WorldMemory.Region("base",p(0,0,0),p(9,100,9)),false);
        byte[] before=Files.readAllBytes(path);
        assertThrows(IllegalArgumentException.class,()->m.protect(new WorldMemory.Region("base",p(0,0,0),p(1,1,1)),false));
        assertThrows(IllegalArgumentException.class,()->m.remove("region","base",false));
        assertArrayEquals(before,Files.readAllBytes(path));
        m.remove("region","base",true);assertTrue(new WorldMemory(path,"world:0").snapshot().regions().isEmpty());
    }
    @Test public void namedRoutesAndWaypointsSurviveRestart() throws Exception {
        Path path=temp.getRoot().toPath().resolve("world.json");WorldMemory m=new WorldMemory(path,"world:0");
        m.waypoint("workshop",p(10,70,5),false);
        m.route(new WorldMemory.Route("base to cave",List.of(p(10,70,5),p(20,70,5),p(20,65,15)),2),false);
        WorldMemory next=new WorldMemory(path,"world:0");assertEquals(m.snapshot(),next.snapshot());
        assertThrows(IllegalArgumentException.class,()->next.waypoint("workshop",p(2,3,4),false));
    }
    @Test public void corruptOrWrongWorldPolicyNeverLoadsAsUnprotected() throws Exception {
        Path path=temp.getRoot().toPath().resolve("world.json");WorldMemory m=new WorldMemory(path,"world:0");
        m.protect(new WorldMemory.Region("base",p(0,0,0),p(9,100,9)),false);
        assertThrows(java.io.IOException.class,()->new WorldMemory(path,"other-world:0"));
        Files.writeString(path,"{broken");
        assertThrows(java.io.IOException.class,()->new WorldMemory(path,"world:0"));
        assertEquals("{broken",Files.readString(path));
    }
    @Test public void failedSaveRetainsPriorMemory() throws Exception {
        Path parent=temp.newFolder().toPath(),path=parent.resolve("world.json");WorldMemory m=new WorldMemory(path,"world:0");
        m.waypoint("old",p(0,1,0),false);WorldMemory.Snapshot before=m.snapshot();
        Files.delete(path);Files.delete(parent);Files.writeString(parent,"blocks directory creation");
        assertThrows(java.io.IOException.class,()->m.waypoint("new",p(1,1,1),false));assertEquals(before,m.snapshot());
    }
    @Test public void routeCompactionPreservesTurnsElevationAndReversals() {
        var path=List.of(p(0,70,0),p(1,70,0),p(2,70,0),p(2,71,0),p(2,72,0),p(2,71,0),p(2,71,1));
        assertEquals(List.of(p(0,70,0),p(2,70,0),p(2,72,0),p(2,71,0),p(2,71,1)),WorldMemory.compact(path));
    }
    @Test public void plannerSnapshotDoesNotChangeUnderLaterPolicyEdits() throws Exception {
        WorldMemory m=new WorldMemory(temp.getRoot().toPath().resolve("world.json"),"world:0");var old=m.snapshot();
        m.protect(new WorldMemory.Region("base",p(0,0,0),p(9,100,9)),false);
        assertTrue(old.protectedAt(p(1,1,1)).isEmpty());assertFalse(m.snapshot().protectedAt(p(1,1,1)).isEmpty());
        assertThrows(UnsupportedOperationException.class,()->m.snapshot().regions().clear());
    }
    @Test public void explicitOverrideDoesNotSurviveControlTakeover() {
        InputArbiter arbiter=new InputArbiter(new InputArbiter.Sink() {
            public void applyKeys(Set<Integer> keys) {} public void applyLook(float yaw,float pitch) {}
        });
        var first=arbiter.acquire("approved mining",reason->{},true);assertTrue(first.overrideProtection());assertTrue(arbiter.current().overrideProtection());
        var next=arbiter.acquire("navigation",reason->{});assertFalse(first.overrideProtection());assertFalse(next.overrideProtection());assertFalse(arbiter.current().overrideProtection());
    }
    @Test public void defaultProtectionAllowsDeliberateWorkWhileStrictRegionsStillLockEdits() throws Exception {
        Path path=temp.getRoot().toPath().resolve("world.json");WorldMemory m=new WorldMemory(path,"world:0");
        m.protect(new WorldMemory.Region("base",p(0,0,0),p(10,100,10)),false);
        m.protect(new WorldMemory.Region("critical floor",p(0,60,0),p(10,60,10),"all_edits"),false);
        var restored=new WorldMemory(path,"world:0").snapshot();
        assertEquals(List.of("base"),restored.protectedAt(p(5,65,5),true));
        assertEquals(List.of(),restored.protectedAt(p(5,65,5),false));
        assertEquals(List.of("critical floor"),restored.protectedAt(p(5,60,5),false));
        assertEquals(List.of("base","critical floor"),restored.protectedAt(p(5,60,5),true));
        assertThrows(IllegalArgumentException.class,()->m.protect(new WorldMemory.Region("critical floor",p(0,60,0),p(10,60,10)),false));
    }
}
