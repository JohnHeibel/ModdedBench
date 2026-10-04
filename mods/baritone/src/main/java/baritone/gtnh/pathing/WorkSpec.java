// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh.pathing;

import baritone.compat.BlockPos;
import java.util.*;

/** Bounded transport-neutral work plans. No game registries or item assumptions. */
public final class WorkSpec {
    private WorkSpec() {}
    public static Map<String,Object> object(Object value) {
        if(!(value instanceof Map<?,?> map)) throw new IllegalArgumentException("object required");
        Map<String,Object> out=new LinkedHashMap<>();for(var e:map.entrySet()) {
            if(!(e.getKey() instanceof String key))throw new IllegalArgumentException("string keys required");out.put(key,e.getValue());
        }return out;
    }
    public static Map<String,Object> child(Map<String,Object> p,String key) {return p.containsKey(key)?object(p.get(key)):Map.of();}
    static void fields(Map<String,Object> p,Set<String> allowed){if(!allowed.containsAll(p.keySet()))throw new IllegalArgumentException("unknown fields: "+p.keySet().stream().filter(k->!allowed.contains(k)).toList());}
    public static List<?> list(Object value) {if(!(value instanceof List<?> list))throw new IllegalArgumentException("array required");return list;}
    public static int integer(Map<String,Object> p,String key,int fallback,int min,int max) {
        Object v=p.getOrDefault(key,fallback);if(!(v instanceof Number n)||!Double.isFinite(n.doubleValue())||n.doubleValue()!=n.longValue()||n.longValue()<min||n.longValue()>max)throw new IllegalArgumentException(key+" must be an integer in "+min+".."+max);return n.intValue();
    }
    public static double number(Map<String,Object> p,String key,double fallback,double min,double max) {
        Object v=p.getOrDefault(key,fallback);if(!(v instanceof Number n)||!Double.isFinite(n.doubleValue())||n.doubleValue()<min||n.doubleValue()>max)throw new IllegalArgumentException("invalid "+key);return n.doubleValue();
    }
    public static boolean bool(Map<String,Object> p,String key,boolean fallback) {
        Object v=p.getOrDefault(key,fallback);if(!(v instanceof Boolean b))throw new IllegalArgumentException(key+" must be boolean");return b;
    }
    public static String string(Map<String,Object> p,String key,String fallback) {
        Object v=p.getOrDefault(key,fallback);if(!(v instanceof String s)||s.length()>4096)throw new IllegalArgumentException("invalid "+key);return s;
    }
    public static BlockPos pos(Object value) {
        List<?> a=list(value);if(a.size()!=3)throw new IllegalArgumentException("position needs three integers");
        return new BlockPos(integer(Map.of("x",a.get(0)),"x",0,-30000000,30000000),integer(Map.of("y",a.get(1)),"y",0,-255,255),integer(Map.of("z",a.get(2)),"z",0,-30000000,30000000));
    }
    public static List<Integer> point(BlockPos p) {return List.of(p.getX(),p.getY(),p.getZ());}
    public record Bounds(BlockPos min,BlockPos max) {
        public Bounds {
            if(min.getX()>max.getX()||min.getY()>max.getY()||min.getZ()>max.getZ()||min.getY()<0||max.getY()>255)throw new IllegalArgumentException("ordered world bounds required");
        }
        public long volume(){return ((long)max.getX()-min.getX()+1)*((long)max.getY()-min.getY()+1)*((long)max.getZ()-min.getZ()+1);}
        public BlockPos at(long index){long h=max.getY()-min.getY()+1,d=max.getZ()-min.getZ()+1;return new BlockPos((int)(min.getX()+index/(h*d)),(int)(min.getY()+index%h),(int)(min.getZ()+(index/h)%d));}
        public boolean contains(BlockPos p){return p.getX()>=min.getX()&&p.getX()<=max.getX()&&p.getY()>=min.getY()&&p.getY()<=max.getY()&&p.getZ()>=min.getZ()&&p.getZ()<=max.getZ();}
    }
    public static Bounds bounds(Map<String,Object> p) {return new Bounds(pos(p.get("min")),pos(p.get("max")));}
    /**
     * The most cells one construction job takes. The job looks at every cell of its plan on every game tick (is it
     * loaded, does it match, what stands in it), a few world reads each; at this many that walk is of the order of a
     * millisecond, which is the whole of what a job may cost the game thread. A larger build is several jobs.
     */
    public static final int CELLS=4096;
    /** The largest box a selection is cut from (a shell or a sphere keeps few of its cells; CELLS bounds what is kept). */
    public static final int SELECTION=262144;
    /**
     * stage: the part of the plan this block belongs to, 0 first (see BuildSteps). anyMeta: the cell named no meta,
     * so any variant of the block satisfies it (a block that faces the way it is placed has no meta to ask for).
     */
    public record Cell(BlockPos pos,String id,int meta,boolean clear,Map<String,Object> item,Map<String,Object> replace,Map<String,Object> verify,int stage,boolean anyMeta) {
        public Cell(BlockPos pos,String id,int meta,boolean clear){this(pos,id,meta,clear,Map.of(),Map.of(),Map.of(),0,false);}
    }
    public static void verification(Map<String,Object> verify) {
        fields(verify,Set.of("pickedItem"));
        if(verify.containsKey("pickedItem")){var item=child(verify,"pickedItem");fields(item,Set.of("id","meta","nbt","ore"));if(!item.containsKey("id")&&!item.containsKey("ore"))throw new IllegalArgumentException("pickedItem needs id or ore");integer(item,"meta",0,0,32767);}
    }
    public static List<Cell> cells(Map<String,Object> spec) {
        // stallTicks and retry are what a resume may add to any job's saved spec (BaritoneNavigation.resume).
        fields(spec,Set.of("name","cells","selection","origin","size","replaceExisting","timeoutTicks","overrideProtection","allowBreak","allowPlace","jobId","stallTicks","retry","_timeout_ms"));
        for(String key:List.of("replaceExisting","overrideProtection","allowBreak","allowPlace"))bool(spec,key,false);
        integer(spec,"timeoutTicks",12000,1,72000);
        if(spec.containsKey("size")){List<?> size=list(spec.get("size"));if(size.size()!=3)throw new IllegalArgumentException("size needs three dimensions");for(int i=0;i<3;i++)integer(Map.of("size",size.get(i)),"size",1,1,i==1?256:30000000);}
        List<Map<String,Object>> entries=new ArrayList<>();
        if(spec.containsKey("cells")==spec.containsKey("selection"))throw new IllegalArgumentException("exactly one of cells or selection required");
        if(spec.containsKey("cells")) {
            List<?> cells=list(spec.get("cells"));if(cells.isEmpty()||cells.size()>CELLS)throw new IllegalArgumentException("cells must contain 1.."+CELLS+" entries; a larger build is several jobs");
            for(Object entry:cells)entries.add(object(entry));
        } else {
            Map<String,Object> sel=object(spec.get("selection"));Bounds bounds=bounds(sel);
            fields(sel,Set.of("min","max","shape","block","replace","axis"));
            if(bounds.volume()>SELECTION)throw new IllegalArgumentException("selection volume exceeds "+SELECTION);
            String shape=string(sel,"shape","fill"),axis=string(sel,"axis","y");if(!Set.of("fill","replace","walls","shell","clear","sphere","hsphere","cylinder","hcylinder").contains(shape))throw new IllegalArgumentException("unknown selection shape");
            if(!Set.of("x","y","z").contains(axis))throw new IllegalArgumentException("invalid cylinder axis");
            if(shape.equals("replace")&&!sel.containsKey("replace"))throw new IllegalArgumentException("replace selector required");
            Map<String,Object> block=shape.equals("clear")?Map.of("clear",true):child(sel,"block");
            for(long i=0;i<bounds.volume();i++) {
                BlockPos p=bounds.at(i);boolean sides=p.getX()==bounds.min.getX()||p.getX()==bounds.max.getX()||p.getZ()==bounds.min.getZ()||p.getZ()==bounds.max.getZ();
                if(shape.equals("walls")&&!sides||shape.equals("shell")&&!sides&&p.getY()!=bounds.min.getY()&&p.getY()!=bounds.max.getY())continue;
                if(Set.of("sphere","hsphere","cylinder","hcylinder").contains(shape)&&!ConstructionMask.contains(shape,axis,p.getX()-bounds.min.getX(),p.getY()-bounds.min.getY(),p.getZ()-bounds.min.getZ(),bounds.max.getX()-bounds.min.getX()+1,bounds.max.getY()-bounds.min.getY()+1,bounds.max.getZ()-bounds.min.getZ()+1))continue;
                Map<String,Object> e=new LinkedHashMap<>(block);e.put("pos",point(p));if(sel.containsKey("replace"))e.put("replace",sel.get("replace"));entries.add(e);
                if(entries.size()>CELLS)throw new IllegalArgumentException("selection holds more than "+CELLS+" cells; a larger build is several jobs");
            }
        }
        BlockPos origin=spec.containsKey("origin")?pos(spec.get("origin")):new BlockPos(0,0,0);
        Set<BlockPos> seen=new HashSet<>();List<Cell> cells=new ArrayList<>();
        for(Map<String,Object> e:entries) {
            if(e.containsKey("tileNbt")||e.containsKey("nbt"))throw new IllegalArgumentException("tile state requires an explicit normal-interaction adapter; do not silently discard schematic NBT");
            fields(e,Set.of("pos","id","meta","clear","item","replace","verify","stage","tile","name"));  // tile, name: what nav.copy saw, so a copy builds as it is; never read
            verification(child(e,"verify"));
            BlockPos local=pos(e.get("pos"));long x=(long)local.getX()+origin.getX(),y=(long)local.getY()+origin.getY(),z=(long)local.getZ()+origin.getZ();
            if(Math.abs(x)>30000000||y<1||y>254||Math.abs(z)>30000000)throw new IllegalArgumentException("translated cell outside world bounds");
            BlockPos p=new BlockPos((int)x,(int)y,(int)z);if(!seen.add(p))throw new IllegalArgumentException("duplicate cell "+p);
            boolean clear=bool(e,"clear",false);String id=string(e,"id","");if(!clear&&(id.isBlank()||!id.contains(":")))throw new IllegalArgumentException("namespaced block id required");
            if(clear&&!child(e,"verify").isEmpty())throw new IllegalArgumentException("clear cells cannot require a picked item");
            int stage=integer(e,"stage",0,0,BuildSteps.STAGES-1);
            if(stage>0&&clear)throw new IllegalArgumentException("a clear cell has no stage: emptying is never delayed");
            cells.add(new Cell(p,id,integer(e,"meta",0,0,15),clear,child(e,"item"),child(e,"replace"),child(e,"verify"),stage,!clear&&!e.containsKey("meta")));
        }
        cells.sort(Comparator.comparingInt((Cell c)->c.clear?0:1).thenComparingInt(c->c.clear?-c.pos.getY():c.pos.getY()).thenComparingInt(c->c.pos.getX()).thenComparingInt(c->c.pos.getZ()));
        return List.copyOf(cells);
    }
}
