// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package baritone.gtnh.pathing;

import baritone.compat.BlockPos;
import java.util.*;
import static baritone.gtnh.pathing.WorkSpec.*;

/**
 * The clicks of a build: a place (a plan cell that says how it is clicked in: a block into a cell by clicking a face
 * of its neighbour) or a use (a right click on a block that stands, with a held item or an empty hand). Parsing, and
 * which click cell goes next: a block clicked against exists before its click, and a block that would hide every way
 * to make another click goes after it. Pure: no game classes.
 */
public final class StepPlan {
    private StepPlan() {}
    /**
     * The most clicks one job takes, click cells and uses together. Each pick of the next click cell looks at every
     * other one still open (next), so the whole order costs their number squared: at this many, a few seconds of the
     * search thread in the worst plan, and none of the game thread.
     */
    public static final int CLICKS=256;
    /** How many ways of making a click are remembered for it: enough that one neighbour rarely takes them all. */
    static final int WAYS=4;
    /** index: of the cell in the plan's cells, or of the use in its uses. meta null: any variant. */
    public record Step(int index,String name,boolean place,BlockPos pos,String id,Integer meta,Map<String,Object> item,ClickSpec click,List<Expectation> expect,int stage) {
        public Vantages.Target target(){return new Vantages.Target(place?Vantages.placing(pos,click.face()):Vantages.using(pos,click.face()),click.hit(),click.look(),place?pos:null);}
        public String label(){return name!=null?name:(place?"place ":"use ")+pos.getX()+","+pos.getY()+","+pos.getZ();}
        /** What the journal knows this click by: the cell of a place, the number of a use. */
        public String key(){return place?pos.getX()+","+pos.getY()+","+pos.getZ():"use"+index;}
        /** A place presses sneak unless told otherwise, so the click lands on the block and never opens it; a use does not. */
        public boolean sneak(){return click.sneak()!=null?click.sneak():place;}
    }
    /** The plan's click cells as places, in the plan's order. */
    public static List<Step> places(List<Cell> cells) {
        List<Step> out=new ArrayList<>();
        for(int i=0;i<cells.size();i++){Cell c=cells.get(i);if(c.click()!=null)out.add(new Step(i,null,true,c.pos(),c.id(),c.anyMeta()?null:c.meta(),c.item(),c.click(),c.expect(),c.stage()));}
        return List.copyOf(out);
    }
    static List<Expectation> expectations(Map<String,Object> e,BlockPos origin,BlockPos self) {
        if(!e.containsKey("expect"))return List.of();
        List<?> rows=list(e.get("expect"));if(rows.size()>4)throw new IllegalArgumentException("at most 4 expectations per click");
        List<Expectation> out=new ArrayList<>();for(Object row:rows)out.add(Expectation.parse(object(row),origin,self));
        return List.copyOf(out);
    }
    /** uses: [{pos, item | {empty:true}, click?, expect?, id?, stage?, name?}], pos relative to the plan's origin, in the order given. */
    public static List<Step> uses(Map<String,Object> spec) {
        if(!spec.containsKey("uses"))return List.of();
        BlockPos origin=spec.containsKey("origin")?pos(spec.get("origin")):new BlockPos(0,0,0);
        List<Step> out=new ArrayList<>();Set<String> names=new HashSet<>();
        for(Object row:list(spec.get("uses"))) {
            var e=object(row);int index=out.size();
            fields(e,Set.of("pos","item","click","expect","id","stage","name"));
            String name=e.containsKey("name")?string(e,"name",""):null;
            if(name!=null&&(name.isBlank()||name.length()>64||!names.add(name)))throw new IllegalArgumentException("use names are unique, 1..64 characters");
            BlockPos p=translated(pos(e.get("pos")),origin);
            String id=e.containsKey("id")?string(e,"id",""):null;
            if(id!=null&&!id.contains(":"))throw new IllegalArgumentException("use "+index+": namespaced block id required");
            Map<String,Object> item=child(e,"item");
            if(item.isEmpty())throw new IllegalArgumentException("use "+index+" needs item: a selector {id, meta?, nbt?, ore?} or {empty: true}");
            if(item.containsKey("empty")&&(item.size()!=1||!bool(item,"empty",false)))throw new IllegalArgumentException("item {empty: true} stands alone");
            out.add(new Step(index,name,false,p,id,null,item,e.containsKey("click")?ClickSpec.parse(child(e,"click")):ClickSpec.ANY,expectations(e,origin,p),integer(e,"stage",0,0,BuildSteps.STAGES-1)));
        }
        return List.copyOf(out);
    }
    /** step: the click cell to place next. hides: an open cell it leaves no way to click, when every candidate hides one. ready: a way to make it was found. */
    public record Pick(Step step,Step hides,boolean ready) {}
    static ClickSpace.Voxel placed(Step s){return ClickSpace.Voxel.full(s.pos(),s.id(),false);}
    /**
     * The click cell to place next, of `open` (the plan's order): the first that can be made now and does not take
     * away every way of making another that can be made now. When each one does, the first of them, with one cell it
     * hides. When none can be made now, the first of all: the search made for that click says what is wrong with it.
     * ways remembers what was found for a cell; the caller drops the entries near a change. A pick then searches only
     * for those, and for a cell whose remembered ways a candidate takes away to the last, so it is complete at any
     * number of cells: nothing is cut short.
     */
    public static Pick next(List<Step> open,ClickSpace now,Vantages.Body body,Map<String,List<Vantages.Vantage>> ways) {
        List<Step> ready=new ArrayList<>();
        for(Step s:open)if(!ways.computeIfAbsent(s.key(),k->now.at(s.pos()).replaceable()?Vantages.search(now,s.target(),body,WAYS):List.of()).isEmpty())ready.add(s);
        if(ready.isEmpty())return new Pick(open.get(0),null,false);
        Pick first=null;
        for(Step c:ready) {
            Step hidden=null;
            for(Step r:ready) {
                if(r==c||!near(r,c,body)||ways.get(r.key()).stream().anyMatch(v->Vantages.survives(v,c.pos())))continue;
                if(Vantages.search(now.with(c.pos(),placed(c)),r.target(),body,1).isEmpty()){hidden=r;break;}
            }
            if(hidden==null)return new Pick(c,null,true);
            if(first==null)first=new Pick(c,hidden,true);
        }
        return first;
    }
    /** Whether a block at b can matter to a click at a: within a reach and a body of each other. */
    public static boolean near(Step a,Step b,Vantages.Body body){return Vantages.sq(a.pos(),b.pos())<=Math.pow(body.reach()+4,2);}
    /**
     * A block went into `cell`: of what is remembered for the open cells near it, the ways it stands in are dropped,
     * and a cell left with none (or that had none) is searched again at the next pick, since the block may be what it
     * is clicked against or stood on.
     */
    public static void placed(Map<String,List<Vantages.Vantage>> ways,List<Step> open,BlockPos cell,Vantages.Body body) {
        for(Step s:open) {
            var known=ways.get(s.key());if(known==null||Vantages.sq(s.pos(),cell)>Math.pow(body.reach()+4,2))continue;
            var left=known.stream().filter(v->Vantages.survives(v,cell)).toList();
            if(left.isEmpty())ways.remove(s.key());else ways.put(s.key(),left);
        }
    }
    /** The blocks copied to pick among these clicks: their box, widened by margin (and two more below, for footing). */
    public static long volume(List<Step> steps,int margin) {
        int[] lo={Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE},hi={Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE};
        for(Step s:steps){int[] c={s.pos().getX(),s.pos().getY(),s.pos().getZ()};for(int i=0;i<3;i++){lo[i]=Math.min(lo[i],c[i]);hi[i]=Math.max(hi[i],c[i]);}}
        return steps.isEmpty()?0:(hi[0]-lo[0]+2L*margin+1)*(hi[1]-lo[1]+2L*margin+3)*(hi[2]-lo[2]+2L*margin+1);
    }
}
