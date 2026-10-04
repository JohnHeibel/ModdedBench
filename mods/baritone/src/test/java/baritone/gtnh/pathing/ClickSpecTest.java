// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package baritone.gtnh.pathing;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class ClickSpecTest {
    @Test public void facesByIndexNameOrAxis() {
        assertEquals(2,ClickSpec.face("north"));assertEquals(2,ClickSpec.face("-z"));assertEquals(5,ClickSpec.face(5));
        assertEquals(4,ClickSpec.face("WEST"));assertEquals(3,ClickSpec.opposite(2));assertEquals(0,ClickSpec.opposite(1));
        assertThrows(IllegalArgumentException.class,()->ClickSpec.face("left"));
        assertThrows(IllegalArgumentException.class,()->ClickSpec.face(6));
    }
    @Test public void omittedFieldsLeaveTheChoiceToTheBuilder() {
        ClickSpec c=ClickSpec.parse(Map.of());
        assertNull(c.face());assertNull(c.hit());assertNull(c.sneak());assertTrue(c.look().any());assertFalse(c.orients());
        assertEquals(Map.of(),c.json());
    }
    @Test public void parsesEveryFieldAndRoundTrips() {
        ClickSpec c=ClickSpec.parse(Map.of("face","up","hit",List.of(.5,1,.25),"look",Map.of("toward","south","pitch",List.of(0,60)),"sneak",false));
        assertEquals(Integer.valueOf(1),c.face());assertEquals(.25,c.hit().z(),1e-9);assertEquals(Boolean.FALSE,c.sneak());assertTrue(c.orients());
        assertEquals(c,ClickSpec.parse(c.json()));
    }
    @Test public void rejectsUnknownFieldsAndBadValues() {
        assertThrows(IllegalArgumentException.class,()->ClickSpec.parse(Map.of("side",1)));
        assertThrows(IllegalArgumentException.class,()->ClickSpec.parse(Map.of("hit",List.of(.5,.5))));
        assertThrows(IllegalArgumentException.class,()->ClickSpec.parse(Map.of("hit",List.of(.5,.5,3))));
        assertThrows(IllegalArgumentException.class,()->ClickSpec.parse(Map.of("look",Map.of("pitch",List.of(30,10)))));
        assertThrows(IllegalArgumentException.class,()->ClickSpec.parse(Map.of("look",Map.of("facing","north"))));
    }
    @Test public void towardUsesThePlayerFacingQuadrant() {
        ClickSpec.Look south=new ClickSpec.Look(3,null,null),west=new ClickSpec.Look(4,null,null),north=new ClickSpec.Look(2,null,null),east=new ClickSpec.Look(5,null,null);
        assertTrue(south.accepts(0,0));assertTrue(south.accepts(44,0));assertFalse(south.accepts(46,0));
        assertTrue(west.accepts(90,0));assertTrue(north.accepts(180,0));assertTrue(north.accepts(-180,0));assertTrue(east.accepts(270,0));assertTrue(east.accepts(-90,0));
        assertTrue(new ClickSpec.Look(0,null,null).accepts(10,50));assertFalse(new ClickSpec.Look(0,null,null).accepts(10,40));
        assertTrue(new ClickSpec.Look(1,null,null).accepts(10,-60));
    }
    @Test public void yawRangesWrapAround() {
        ClickSpec.Look l=ClickSpec.parse(Map.of("look",Map.of("yaw",List.of(350,370)))).look();
        assertTrue(l.accepts(0,0));assertTrue(l.accepts(355,0));assertTrue(l.accepts(-5,0));assertFalse(l.accepts(20,0));assertFalse(l.accepts(340,0));
        assertTrue(ClickSpec.parse(Map.of("look",Map.of("yaw",List.of(0,360)))).look().any());
        ClickSpec.Look exact=ClickSpec.parse(Map.of("look",Map.of("pitch",30))).look();
        assertTrue(exact.accepts(0,30));assertFalse(exact.accepts(0,31));
    }
}
