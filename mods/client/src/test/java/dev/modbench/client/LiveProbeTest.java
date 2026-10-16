// SPDX-License-Identifier: MIT
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.client;

import com.google.gson.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class LiveProbeTest {
    static class Hex {int type=1;String aspect="aer";}
    static class Note {
        static String KEY="research";
        private final Map<String,Hex> hexes=new LinkedHashMap<>();
        int[] counts={3,1,4};
        List<String> tags=new ArrayList<>(List.of("a","b","c"));
        Note self=this;
        double broken=Double.NaN;
        Note(){hexes.put("0:0",new Hex());hexes.put("1:-1",new Hex());}
    }
    static class Scroll extends Note {boolean done;}
    private static JsonArray path(Object... steps){return new Gson().toJsonTree(steps).getAsJsonArray();}

    @Test public void walksFieldsIndicesKeysAndAlternativesIncludingPrivateAndInherited() throws Exception {
        Scroll s=new Scroll();
        assertEquals("aer",LiveProbe.walk(s,path("hexes","1:-1","aspect")));
        assertEquals(4,LiveProbe.walk(s,path("counts","2")));
        assertEquals("b",LiveProbe.walk(s,path("tags","1")));
        assertEquals(1,LiveProbe.walk(s,path(new String[]{"field_123_a","hexes"},"0:0","type")));  // the first alternative that exists
        assertEquals("research",LiveProbe.walk(new LiveProbe.Statics(Note.class),path("KEY")));
    }

    @Test public void aMissingFieldNamesTheOnesThatExist() {
        Exception e=assertThrows(IllegalArgumentException.class,()->LiveProbe.walk(new Note(),path("grid")));
        assertTrue(e.getMessage(),e.getMessage().contains("no field [grid] on dev.modbench.client.LiveProbeTest$Note; it has [KEY, hexes, counts, tags, self, broken]"));
        assertThrows(IllegalArgumentException.class,()->LiveProbe.walk(new Note(),path("counts","3")));
    }

    @Test public void rendersToDepthWithinLimitsAndStopsAtCycles() {
        JsonObject note=new LiveProbe(2).render(new Note(),2).getAsJsonObject();
        assertEquals("dev.modbench.client.LiveProbeTest$Note",note.get("@class").getAsString());
        assertFalse(note.has("KEY"));  // statics show only from a class root
        assertEquals("[3,1,\"1 more\"]",note.get("counts").toString());
        assertEquals("{\"@class\":\"dev.modbench.client.LiveProbeTest$Hex\"}",note.getAsJsonObject("hexes").get("0:0").toString());  // depth ran out: a longer path opens it
        assertEquals("{\"@class\":\"dev.modbench.client.LiveProbeTest$Note\"}",note.get("self").toString());
        assertEquals("NaN",note.get("broken").getAsString());
        assertEquals(1,new LiveProbe(32).render(new Note(),3).getAsJsonObject().getAsJsonObject("hexes").getAsJsonObject("1:-1").get("type").getAsInt());
        assertEquals("research",new LiveProbe(32).render(new LiveProbe.Statics(Note.class),1).getAsJsonObject().get("KEY").getAsString());
    }
}
