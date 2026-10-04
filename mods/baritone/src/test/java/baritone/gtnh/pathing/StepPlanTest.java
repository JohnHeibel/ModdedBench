// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package baritone.gtnh.pathing;

import baritone.compat.BlockPos;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
import static baritone.gtnh.pathing.Spaces.*;

public class StepPlanTest {
    private static Map<String,Object> cell(int x,int y,int z,Map<String,Object> click){Map<String,Object> m=new LinkedHashMap<>(Map.of("pos",List.of(x,y,z),"id","minecraft:stone"));if(click!=null)m.put("click",click);return m;}
    private static List<StepPlan.Step> places(Object... cells){return StepPlan.places(WorkSpec.cells(Map.of("cells",List.of(cells))));}
    private static Map<String,Object> use(Object... fields){Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<fields.length;i+=2)m.put((String)fields[i],fields[i+1]);return m;}
    /** The job's loop: pick, place, forget what the block took away; stops at the first pick that is not ready. */
    private static List<BlockPos> order(List<StepPlan.Step> steps,ClickSpace w) {
        List<StepPlan.Step> open=new ArrayList<>(steps);Map<String,List<Vantages.Vantage>> ways=new HashMap<>();List<BlockPos> out=new ArrayList<>();
        while(!open.isEmpty()) {
            var pick=StepPlan.next(open,w,BODY,ways);if(!pick.ready())break;
            open.remove(pick.step());out.add(pick.step().pos());
            w=w.with(pick.step().pos(),ClickSpace.Voxel.full(pick.step().pos(),pick.step().id(),false));StepPlan.placed(ways,open,pick.step().pos(),BODY);
        }
        return out;
    }
    @Test public void aPlansClickCellsArePlacesAndTheRestIsNot() {
        var cells=WorkSpec.cells(Map.of("origin",List.of(10,60,10),"cells",List.of(cell(0,1,0,null),cell(1,1,0,Map.of("look",Map.of("toward","north"))),
            Map.of("pos",List.of(2,1,0),"id","mod:machine","meta",3,"stage",2,"expect",List.of(Map.of("method","obs.block","path","meta","equals",3))))));
        var s=StepPlan.places(cells);
        assertEquals(2,s.size());assertTrue(s.get(0).place());assertEquals(p(11,61,10),s.get(0).pos());assertEquals("11,61,10",s.get(0).key());
        assertEquals(cells.get(s.get(0).index()).pos(),s.get(0).pos());assertNull("no meta named: any variant",s.get(0).meta());assertTrue("a place sneaks unless told otherwise",s.get(0).sneak());
        assertEquals("an expect alone makes a click cell",ClickSpec.ANY,s.get(1).click());assertEquals(Integer.valueOf(3),s.get(1).meta());assertEquals(2,s.get(1).stage());
        assertEquals("the read is of the cell itself",List.of(12,61,10),s.get(1).expect().get(0).request().get("pos"));
    }
    @Test public void usesAreParsedRelativeToTheOriginInTheOrderGiven() {
        var s=StepPlan.uses(Map.of("origin",List.of(10,60,10),"uses",List.of(
            use("name","wrench","pos",List.of(0,1,0),"item",Map.of("id","mod:wrench"),"click",Map.of("face","north","hit",List.of(.5,.5,0)),"expect",List.of(Map.of("method","obs.block","path","meta","changed",true))),
            use("pos",List.of(0,2,0),"item",Map.of("empty",true),"id","mod:machine","stage",1))));
        assertEquals(2,s.size());assertFalse(s.get(0).place());assertEquals(p(10,61,10),s.get(0).pos());assertEquals("wrench",s.get(0).label());assertEquals("use0",s.get(0).key());
        assertFalse("a use does not sneak unless told to",s.get(0).sneak());assertEquals(List.of(10,61,10),s.get(0).expect().get(0).request().get("pos"));
        assertEquals("use 10,62,10",s.get(1).label());assertEquals(1,s.get(1).stage());assertEquals("mod:machine",s.get(1).id());
        assertEquals(1,s.get(0).target().clicks().size());assertEquals(6,s.get(1).target().clicks().size());
        assertTrue(StepPlan.uses(Map.of("cells",List.of())).isEmpty());
    }
    @Test public void rejectsMalformedUses() {
        for(var bad:List.of(use("pos",List.of(0,64,0)),use("pos",List.of(0,64,0),"item",Map.of("empty",true,"id","x:y")),use("pos",List.of(0,64,0),"item",Map.of("empty",false)),
                use("pos",List.of(0,64,0),"item",Map.of("empty",true),"id","machine"),use("pos",List.of(0,64,0),"item",Map.of("empty",true),"kind","use"),use("item",Map.of("empty",true))))
            assertThrows(bad.toString(),IllegalArgumentException.class,()->StepPlan.uses(Map.of("uses",List.of(bad))));
        var named=use("name","a","pos",List.of(0,64,0),"item",Map.of("empty",true));
        assertThrows(IllegalArgumentException.class,()->StepPlan.uses(Map.of("uses",List.of(named,named))));
        var e=Map.<String,Object>of("method","obs.block","changed",true);
        assertThrows(IllegalArgumentException.class,()->StepPlan.uses(Map.of("uses",List.of(use("pos",List.of(0,64,0),"item",Map.of("empty",true),"expect",List.of(e,e,e,e,e))))));
    }
    /** A two-high tunnel along x from (0,66,0); walls, floor and ceiling are stone. */
    private static Spaces tunnel(int length) {
        Spaces s=new Spaces(-1,64,-1,length+1,68,1,true);
        for(int x=0;x<length;x++){s.air(x,66,0);s.air(x,67,0);}
        return s;
    }
    @Test public void supportsGoBeforeTheBlocksPlacedAgainstThem() {
        ClickSpace w=new Spaces(-4,63,-4,4,70,4,false).floor(63).floor(64).build();
        var steps=places(cell(1,65,0,Map.of("face","east")),cell(0,66,0,Map.of("face","up")),cell(0,65,0,Map.of("face","up")));
        var first=StepPlan.next(steps,w,BODY,new HashMap<>());
        assertTrue(first.ready());assertEquals(p(0,65,0),first.step().pos());assertNull(first.hides());
        assertEquals("both wait for the block they are clicked against",List.of(p(0,65,0),p(1,65,0),p(0,66,0)),order(steps,w));
    }
    @Test public void aBlockThatWouldHideALaterClickGoesAfterIt() {
        ClickSpace w=tunnel(3).build();
        assertEquals(List.of(p(2,66,0),p(1,66,0)),order(places(cell(1,66,0,Map.of("face","up")),cell(2,66,0,Map.of("face","up"))),w));
    }
    @Test public void cellsThatHideEachOtherAreSaidWithThePick() {
        // A is clicked from the west end looking east, B from the east end looking west; each line of sight crosses the other's cell.
        ClickSpace w=tunnel(4).build();
        var a=cell(2,66,0,Map.of("face","up","look",Map.of("toward","east","pitch",List.of(0,50))));
        var b=cell(1,66,0,Map.of("face","up","look",Map.of("toward","west","pitch",List.of(0,50))));
        var pick=StepPlan.next(places(a,b),w,BODY,new HashMap<>());
        assertTrue(pick.ready());assertEquals(p(1,66,0),pick.step().pos());assertEquals(p(2,66,0),pick.hides().pos());
    }
    @Test public void whenNoneCanBeMadeTheFirstIsHandedBackNotReady() {
        ClickSpace w=new Spaces(-4,63,-4,4,70,4,false).floor(63).floor(64).build();
        var pick=StepPlan.next(places(cell(0,68,0,Map.of("face","up")),cell(2,68,0,null)),w,BODY,new HashMap<>());
        assertFalse(pick.ready());assertEquals(p(0,68,0),pick.step().pos());
        assertEquals("support_missing",Vantages.problem(w,pick.step().target(),new Vantages.Tally()));
        // A cell that holds a block has no way either: the job says occupied.
        assertFalse(StepPlan.next(places(cell(0,64,0,Map.of())),w,BODY,new HashMap<>()).ready());
    }
    @Test public void theOrderIsCompleteAtTheCapWithNothingCutShort() {
        // A 16 x 16 floor of click cells: every one is another's standing place, so remembered ways die all the time.
        ClickSpace w=new Spaces(-3,63,-3,19,69,19,false).floor(63).floor(64).build();
        List<Object> cells=new ArrayList<>();for(int x=0;x<16;x++)for(int z=0;z<16;z++)cells.add(cell(x,65,z,Map.of("face","up")));
        var steps=places(cells.toArray());assertEquals(StepPlan.CLICKS,steps.size());
        var done=order(steps,w);
        assertEquals(256,done.size());assertEquals(256,new HashSet<>(done).size());
    }
    @Test public void aPlacedBlockDropsOnlyTheWaysItTakesAway() {
        ClickSpace w=new Spaces(-4,63,-4,4,70,4,false).floor(63).floor(64).build();
        var steps=places(cell(0,65,0,Map.of("face","up")),cell(3,65,3,Map.of("face","up")));
        Map<String,List<Vantages.Vantage>> ways=new HashMap<>();StepPlan.next(steps,w,BODY,ways);
        var known=ways.get("0,65,0");assertEquals(StepPlan.WAYS,known.size());
        StepPlan.placed(ways,steps,known.get(0).feet(),BODY);
        assertEquals(StepPlan.WAYS-1,ways.get("0,65,0").size());assertFalse(ways.get("0,65,0").contains(known.get(0)));
        for(var v:List.copyOf(ways.get("0,65,0")))StepPlan.placed(ways,steps,v.feet(),BODY);
        assertFalse("none left: searched again at the next pick",ways.containsKey("0,65,0"));assertTrue(ways.containsKey("3,65,3"));
        ways.put("0,65,0",List.of());StepPlan.placed(ways,steps,p(0,64,1),BODY);
        assertFalse("a cell that had no way is asked again when a block appears near it",ways.containsKey("0,65,0"));
    }
    @Test public void theCopyForAPickIsTheBoxOfItsCellsAndAMargin() {
        assertEquals(0,StepPlan.volume(List.of(),7));
        assertEquals(15L*17*15,StepPlan.volume(places(cell(0,65,0,Map.of())),7));
        assertEquals(18L*19*15,StepPlan.volume(places(cell(0,65,0,Map.of()),cell(3,67,0,Map.of())),7));
    }
}
