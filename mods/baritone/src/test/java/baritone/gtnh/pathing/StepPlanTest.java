// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package baritone.gtnh.pathing;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
import static baritone.gtnh.pathing.Spaces.*;

public class StepPlanTest {
    private static Map<String,Object> place(int x,int y,int z,Map<String,Object> click){Map<String,Object> m=new LinkedHashMap<>(Map.of("kind","place","pos",List.of(x,y,z),"id","minecraft:stone"));if(click!=null)m.put("click",click);return m;}
    private static List<StepPlan.Step> steps(Object... rows){return StepPlan.parse(Map.of("steps",List.of(rows)));}
    private static List<Integer> sequence(StepPlan.Order o){return o.steps().stream().map(StepPlan.Step::index).toList();}
    @Test public void parsesPlaceAndUseStepsRelativeToOrigin() {
        var s=StepPlan.parse(Map.of("origin",List.of(10,60,10),"steps",List.of(
            Map.of("name","hopper","pos",List.of(0,1,0),"id","minecraft:hopper","click",Map.of("face","north")),
            Map.of("kind","use","pos",List.of(0,1,0),"item",Map.of("id","gregtech:wrench"),"expect",List.of(Map.of("method","obs.block","pos",List.of(0,1,0),"path","meta","equals",3))))));
        assertEquals(2,s.size());assertTrue(s.get(0).place());assertEquals(p(10,61,10),s.get(0).pos());assertEquals("hopper",s.get(0).label());
        assertFalse(s.get(1).place());assertEquals(List.of(10,61,10),s.get(1).expect().get(0).request().get("pos"));
        assertEquals(List.of(0,1,0),s.get(1).json(p(10,60,10)).get("pos"));
        assertTrue(StepPlan.isSteps(Map.of("steps",List.of())));assertFalse(StepPlan.isSteps(Map.of("cells",List.of())));
    }
    @Test public void rejectsMalformedSteps() {
        assertThrows(IllegalArgumentException.class,()->steps(Map.of("pos",List.of(0,64,0))));
        assertThrows(IllegalArgumentException.class,()->steps(Map.of("kind","use","pos",List.of(0,64,0))));
        assertThrows(IllegalArgumentException.class,()->steps(place(0,64,0,null),place(0,64,0,null)));
        assertThrows(IllegalArgumentException.class,()->steps(Map.of("name","a","pos",List.of(0,64,0),"id","x:y"),Map.of("name","a","pos",List.of(1,64,0),"id","x:y")));
        assertThrows(IllegalArgumentException.class,()->steps(Map.of("pos",List.of(0,64,0),"id","x:y","item",Map.of("empty",true))));
        assertThrows(IllegalArgumentException.class,()->StepPlan.parse(Map.of("steps",List.of(place(0,64,0,null)),"cells",List.of())));
        assertThrows(IllegalArgumentException.class,()->StepPlan.parse(Map.of("steps",List.of(place(0,64,0,null)),"access",Map.of("allow",true,"maxCells",20))));
        assertThrows(IllegalArgumentException.class,()->StepPlan.parse(Map.of("steps",List.of())));
        var e=Map.<String,Object>of("method","obs.block","changed",true);
        assertThrows(IllegalArgumentException.class,()->steps(Map.of("kind","use","pos",List.of(0,64,0),"item",Map.of("empty",true),"expect",List.of(e,e,e,e,e))));
        assertEquals(1,steps(Map.of("kind","use","pos",List.of(0,64,0),"item",Map.of("empty",true))).size());
    }
    /** A two-high tunnel along x from (0,66,0); walls, floor and ceiling are stone. */
    private static Spaces tunnel(int length) {
        Spaces s=new Spaces(-1,64,-1,length+1,68,1,true);
        for(int x=0;x<length;x++){s.air(x,66,0);s.air(x,67,0);}
        return s;
    }
    @Test public void supportsGoBeforeTheBlocksPlacedAgainstThem() {
        ClickSpace w=new Spaces(-4,63,-4,4,70,4,false).floor(63).floor(64).build();
        var o=StepPlan.order(steps(place(0,66,0,Map.of("face","up")),place(0,65,0,Map.of("face","up"))),w,BODY,500);
        assertEquals(List.of(1,0),sequence(o));
        assertTrue(o.conflicts().isEmpty());assertTrue(o.impossible().isEmpty());assertFalse(o.truncated());
    }
    @Test public void aBlockThatWouldHideALaterClickGoesAfterIt() {
        ClickSpace w=tunnel(3).build();
        var o=StepPlan.order(steps(place(1,66,0,Map.of("face","up")),place(2,66,0,Map.of("face","up"))),w,BODY,500);
        assertEquals(List.of(1,0),sequence(o));
        assertTrue(o.conflicts().isEmpty());
    }
    @Test public void stepsThatHideEachOtherAreReportedAsAConflict() {
        // A is clicked from the west end looking east, B from the east end looking west; each line of sight crosses the other's cell.
        ClickSpace w=tunnel(4).build();
        var a=place(2,66,0,Map.of("face","up","look",Map.of("toward","east","pitch",List.of(0,50))));
        var b=place(1,66,0,Map.of("face","up","look",Map.of("toward","west","pitch",List.of(0,50))));
        var o=StepPlan.order(steps(a,b),w,BODY,500);
        assertEquals(1,o.conflicts().size());
        assertEquals(0,o.conflicts().get(0).get("step"));assertEquals(List.of(1),o.conflicts().get(0).get("hides"));
    }
    @Test public void impossibleStepsKeepTheirOrderWithTheirProblem() {
        ClickSpace w=new Spaces(-4,63,-4,4,70,4,false).floor(63).floor(64).build();
        var o=StepPlan.order(steps(place(0,68,0,Map.of("face","up")),place(0,64,0,null)),w,BODY,500);
        assertEquals("support_missing",o.impossible().get(0));assertEquals("occupied",o.impossible().get(1));
        assertEquals(List.of(0,1),sequence(o));
    }
    @Test public void theSearchBudgetTruncatesInsteadOfRunningLong() {
        ClickSpace w=new Spaces(-4,63,-4,4,70,4,false).floor(63).floor(64).build();
        List<Object> rows=new ArrayList<>();for(int x=-3;x<=3;x++)rows.add(place(x,65,0,Map.of("face","up")));
        var o=StepPlan.order(StepPlan.parse(Map.of("steps",rows)),w,BODY,3);
        assertTrue(o.truncated());assertEquals(7,o.steps().size());
    }
}
