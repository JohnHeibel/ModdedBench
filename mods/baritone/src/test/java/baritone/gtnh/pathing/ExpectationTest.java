// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package baritone.gtnh.pathing;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
import static baritone.gtnh.pathing.Spaces.*;

public class ExpectationTest {
    private static Expectation parse(Map<String,Object> m){return Expectation.parse(m,p(100,0,-100),p(7,70,7));}
    @Test public void extractsKeysAndIndices() {
        Map<String,Object> r=Map.of("block",Map.of("meta",3,"sides",List.of(Map.of("cover","x"),Map.of("cover","y"))));
        assertEquals(3,Expectation.extract(r,"block.meta"));assertEquals("y",Expectation.extract(r,"block.sides[1].cover"));
        assertSame(Expectation.MISSING,Expectation.extract(r,"block.sides[5].cover"));assertSame(Expectation.MISSING,Expectation.extract(r,"block.meta.x"));
        assertEquals(r,Expectation.extract(r,""));
        assertThrows(IllegalArgumentException.class,()->Expectation.extract(r,"a..b"));
    }
    @Test public void comparesAsJson() {
        var eq=parse(Map.of("method","obs.block","path","meta","equals",3));
        assertTrue(eq.met(null,3.0));assertTrue(eq.met(null,3L));assertFalse(eq.met(null,4));assertFalse(eq.met(null,Expectation.MISSING));
        var has=parse(Map.of("method","obs.tile","path","connections","contains",List.of("north","east")));
        assertTrue(has.met(null,List.of("east","up","north")));assertFalse(has.met(null,List.of("east")));assertFalse(has.met(null,"north"));
        var text=parse(Map.of("method","obs.waila","path","lines[0]","contains","Facing"));
        assertTrue(text.met(null,"Facing: north"));
        var changed=parse(Map.of("method","obs.block","path","meta","changed",true));
        assertTrue(changed.met(2,3));assertFalse(changed.met(3,3.0));assertTrue(changed.met(Expectation.MISSING,3));
    }
    @Test public void positionsAreRelativeToTheOriginAndDefaultToTheClickedBlock() {
        var e=parse(Map.of("method","obs.block","pos",List.of(1,64,2),"params",Map.of("full",true),"equals","x"));
        assertEquals(List.of(101,64,-98),e.request().get("pos"));assertEquals(true,e.request().get("full"));
        assertEquals(List.of(7,70,7),parse(Map.of("method","obs.block","path","meta","equals",3)).request().get("pos"));
        assertEquals(Map.of("method","obs.block","params",Map.of("pos",List.of(7,70,7)),"path","meta","equals",3),parse(Map.of("method","obs.block","path","meta","equals",3)).json());
    }
    @Test public void rejectsWhatItCannotRun() {
        assertThrows(IllegalArgumentException.class,()->parse(Map.of("method","act.use_block","equals",1)));
        assertThrows(IllegalArgumentException.class,()->parse(Map.of("method","obs.block")));
        assertThrows(IllegalArgumentException.class,()->parse(Map.of("method","obs.block","equals",1,"contains",1)));
        assertThrows(IllegalArgumentException.class,()->parse(Map.of("method","obs.block","changed",false)));
        assertThrows(IllegalArgumentException.class,()->parse(Map.of("method","obs.block","equals",1,"when","later")));
    }
    @Test public void anIndexNoListCouldHoldIsMissingNotAnError() {
        assertSame(Expectation.MISSING,Expectation.extract(java.util.Map.of("a",java.util.List.of(1,2)),"a[99999999999]"));
    }
}
