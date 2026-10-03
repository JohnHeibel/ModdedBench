// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package baritone.gtnh.pathing;

import baritone.compat.BlockPos;
import java.util.*;
import static baritone.gtnh.pathing.WorkSpec.*;

/**
 * A build as click steps: place (a block into a cell by clicking a face of its neighbour) or use (a click on a block
 * with a held item, such as a tool or a cover). Parsing, and the order the builder runs them in: a block clicked
 * against exists before its click, and a step whose block would hide every way to make a later click goes after it.
 * Pure: no game classes.
 */
public final class StepPlan {
    private StepPlan() {}
    public static final int MAX_STEPS=128;
    public record Step(int index,String name,boolean place,BlockPos pos,String id,Integer meta,Map<String,Object> item,ClickSpec click,List<Expectation> expect,boolean verifyAfterPlacement) {
        public Vantages.Target target(){return new Vantages.Target(place?Vantages.placing(pos,click.face()):Vantages.using(pos,click.face()),click.hit(),click.look(),place?pos:null);}
        public String label(){return name!=null?name:(place?"place ":"use ")+pos.getX()+","+pos.getY()+","+pos.getZ();}
        public Map<String,Object> json(BlockPos origin) {
            Map<String,Object> out=new LinkedHashMap<>();
            if(name!=null)out.put("name",name);
            out.put("kind",place?"place":"use");out.put("pos",List.of(pos.getX()-origin.getX(),pos.getY()-origin.getY(),pos.getZ()-origin.getZ()));
            if(id!=null)out.put("id",id);if(meta!=null)out.put("meta",meta);if(!item.isEmpty())out.put("item",item);
            var c=click.json();if(!c.isEmpty())out.put("click",c);
            if(!expect.isEmpty())out.put("expect",expect.stream().map(e->e.json(origin)).toList());
            if(verifyAfterPlacement)out.put("verifyAfterPlacement",true);
            return out;
        }
    }
    static final Set<String> SPEC=Set.of("name","steps","origin","mode","timeoutTicks","overrideProtection","allowBreak","allowPlace","jobId","_timeout_ms","stallTicks","access","replaceExisting");
    public static boolean isSteps(Map<String,Object> spec){return spec.containsKey("steps");}
    public static List<Step> parse(Map<String,Object> spec) {
        fields(spec,SPEC);
        if(spec.containsKey("cells")||spec.containsKey("selection"))throw new IllegalArgumentException("give steps, or cells/selection, not both");
        for(String key:List.of("overrideProtection","allowBreak","allowPlace","replaceExisting"))bool(spec,key,false);
        integer(spec,"timeoutTicks",12000,1,72000);
        if(spec.containsKey("access"))Access.parse(child(spec,"access"));
        BlockPos origin=spec.containsKey("origin")?pos(spec.get("origin")):new BlockPos(0,0,0);
        List<?> rows=list(spec.get("steps"));
        if(rows.isEmpty()||rows.size()>MAX_STEPS)throw new IllegalArgumentException("steps must contain 1.."+MAX_STEPS+" entries");
        List<Step> out=new ArrayList<>();Set<BlockPos> placed=new HashSet<>();Set<String> names=new HashSet<>();
        for(Object row:rows) {
            var e=object(row);int index=out.size();
            fields(e,Set.of("name","kind","pos","id","meta","item","click","expect","verifyAfterPlacement"));
            String kind=string(e,"kind","place");if(!Set.of("place","use").contains(kind))throw new IllegalArgumentException("step kind is place or use");
            boolean place=kind.equals("place");
            String name=e.containsKey("name")?string(e,"name",""):null;
            if(name!=null&&(name.isBlank()||name.length()>64||!names.add(name)))throw new IllegalArgumentException("step names are unique, 1..64 characters");
            BlockPos local=pos(e.get("pos"));long x=(long)local.getX()+origin.getX(),y=(long)local.getY()+origin.getY(),z=(long)local.getZ()+origin.getZ();
            if(Math.abs(x)>30000000||y<1||y>254||Math.abs(z)>30000000)throw new IllegalArgumentException("step "+index+" outside world bounds");
            BlockPos p=new BlockPos((int)x,(int)y,(int)z);
            String id=e.containsKey("id")?string(e,"id",""):null;
            if(place&&(id==null||!id.matches("[^\\s:]+:[^\\s:]+")))throw new IllegalArgumentException("place step "+index+" needs a namespaced block id");
            if(id!=null&&!id.matches("[^\\s:]+:[^\\s:]+"))throw new IllegalArgumentException("step "+index+": namespaced block id required");
            if(place&&!placed.add(p))throw new IllegalArgumentException("two place steps into "+p);
            Integer meta=e.containsKey("meta")?integer(e,"meta",0,0,15):null;
            Map<String,Object> item=e.containsKey("item")?child(e,"item"):Map.of();
            if(!place&&item.isEmpty())throw new IllegalArgumentException("use step "+index+" needs item: a selector {id,meta?,nbt?,ore?} or {empty:true}");
            if(item.containsKey("empty")&&(item.size()!=1||!bool(item,"empty",false)))throw new IllegalArgumentException("item {empty:true} stands alone");
            if(place&&item.containsKey("empty"))throw new IllegalArgumentException("a placement needs a block item");
            ClickSpec click=e.containsKey("click")?ClickSpec.parse(child(e,"click")):ClickSpec.ANY;
            List<Expectation> expect=new ArrayList<>();
            if(e.containsKey("expect")){List<?> ex=list(e.get("expect"));if(ex.size()>4)throw new IllegalArgumentException("at most 4 expectations per step");for(Object x2:ex)expect.add(Expectation.parse(object(x2),origin));}
            out.add(new Step(index,name,place,p,id,meta,item,click,List.copyOf(expect),bool(e,"verifyAfterPlacement",false)));
        }
        return List.copyOf(out);
    }
    /** The order to run, and what ordering found: steps that undo each other's views, and steps with no way at all. */
    public record Order(List<Step> steps,List<Map<String,Object>> conflicts,Map<Integer,String> impossible,boolean truncated,int searches) {
        public Map<String,Object> json() {
            Map<String,Object> out=new LinkedHashMap<>();
            out.put("sequence",steps.stream().map(Step::index).toList());out.put("conflicts",conflicts);
            Map<String,String> imp=new LinkedHashMap<>();impossible.forEach((k,v)->imp.put(String.valueOf(k),v));out.put("impossible",imp);
            out.put("truncated",truncated);out.put("searches",searches);return out;
        }
    }
    static ClickSpace.Voxel placed(Step s){return ClickSpace.Voxel.full(s.pos(),s.id(),false);}
    /**
     * Greedy order over a hypothetical world (the world plus the steps already ordered, each a full block): take, in the
     * given order, the first step that can be made now and does not hide every vantage of another step that can be made
     * now. When every such step hides another, take the first anyway and report the pair (a cycle: temporary access or a
     * different plan). Steps that cannot be made at all keep the given order at the end, with their problem.
     * budget bounds the vantage searches; past it the rest keep the given order (truncated).
     */
    public static Order order(List<Step> steps,ClickSpace world,Vantages.Body body,int budget) {
        List<Step> remaining=new ArrayList<>(steps),out=new ArrayList<>();
        List<Map<String,Object>> conflicts=new ArrayList<>();Map<Integer,String> impossible=new LinkedHashMap<>();
        int[] searches={0};ClickSpace w=world;
        while(!remaining.isEmpty()) {
            if(searches[0]>budget){out.addAll(remaining);return new Order(out,conflicts,impossible,true,searches[0]);}
            ClickSpace now=w;
            Map<Integer,Boolean> doable=new HashMap<>();
            for(Step s:remaining)doable.put(s.index(),doable(s,now,body,searches));
            List<Step> ready=remaining.stream().filter(s->doable.get(s.index())).toList();
            if(ready.isEmpty()) {
                for(Step s:remaining){
                    Vantages.Tally t=new Vantages.Tally();
                    if(s.place()&&!now.at(s.pos()).replaceable())impossible.put(s.index(),"occupied");
                    else {Vantages.search(now,s.target(),body,1,t);impossible.put(s.index(),Vantages.problem(now,s.target(),t));}
                    out.add(s);
                }
                break;
            }
            Step chosen=null;List<Step> hidden=List.of();
            for(Step c:ready) {
                if(!c.place()){chosen=c;hidden=List.of();break;}
                ClickSpace next=now.with(c.pos(),placed(c));
                List<Step> h=new ArrayList<>();
                for(Step r:ready)if(r!=c&&near(r,c,body)&&!doable(r,next,body,searches))h.add(r);
                if(h.isEmpty()){chosen=c;hidden=List.of();break;}
                if(chosen==null||h.size()<hidden.size()){chosen=c;hidden=h;}
            }
            if(!hidden.isEmpty())conflicts.add(Map.of("step",chosen.index(),"hides",hidden.stream().map(Step::index).toList()));
            out.add(chosen);remaining.remove(chosen);
            if(chosen.place())w=now.with(chosen.pos(),placed(chosen));
        }
        return new Order(out,conflicts,impossible,false,searches[0]);
    }
    static boolean near(Step a,Step b,Vantages.Body body){return Vantages.sq(a.pos(),b.pos())<=Math.pow(body.reach()+4,2);}
    static boolean doable(Step s,ClickSpace w,Vantages.Body body,int[] searches) {
        if(s.place()&&!w.at(s.pos()).replaceable())return false;
        if(!s.place()&&!Vantages.clickable(w,s.pos()))return false;
        searches[0]++;
        return !Vantages.search(w,s.target(),body,1).isEmpty();
    }
}
