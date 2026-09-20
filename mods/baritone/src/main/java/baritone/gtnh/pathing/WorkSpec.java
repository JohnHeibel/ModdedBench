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
    public record Cell(BlockPos pos,String id,int meta,boolean clear,Map<String,Object> item,Map<String,Object> placement,Map<String,Object> replace,Map<String,Object> verify) {
        public Cell(BlockPos pos,String id,int meta,boolean clear,Map<String,Object> item,Map<String,Object> placement,Map<String,Object> replace){this(pos,id,meta,clear,item,placement,replace,Map.of());}
    }
    public static void verification(Map<String,Object> verify) {
        fields(verify,Set.of("pickedItem"));
        if(verify.containsKey("pickedItem")){var item=child(verify,"pickedItem");fields(item,Set.of("id","meta","nbt","ore"));if(!item.containsKey("id")&&!item.containsKey("ore"))throw new IllegalArgumentException("pickedItem needs id or ore");integer(item,"meta",0,0,32767);}
    }
    public static void placement(Map<String,Object> p) {
        fields(p,Set.of("face","hit","yaw","pitch","verifyAfterPlacement"));
        if(p.containsKey("face"))integer(p,"face",0,0,5);
        number(p,"yaw",0,-360000,360000);number(p,"pitch",0,-90,90);bool(p,"verifyAfterPlacement",false);
        if(p.containsKey("hit")){var hit=list(p.get("hit"));if(hit.size()!=3)throw new IllegalArgumentException("hit needs three coordinates");for(Object value:hit)number(Map.of("hit",value),"hit",0,-16,16);}
    }
    public static List<Cell> cells(Map<String,Object> spec) {
        fields(spec,Set.of("name","cells","selection","origin","size","mode","settings","replaceExisting","timeoutTicks","overrideProtection","allowBreak","allowPlace","jobId","_timeout_ms"));
        for(String key:List.of("replaceExisting","overrideProtection","allowBreak","allowPlace"))bool(spec,key,false);
        integer(spec,"timeoutTicks",12000,1,72000);
        String mode=string(spec,"mode","blueprint");if(!Set.of("blueprint","builder").contains(mode))throw new IllegalArgumentException("unknown construction mode");
        ConstructionSettings settings=new ConstructionSettings(child(spec,"settings"));
        // Metadata masks say which variants satisfy a cell, which a strict blueprint needs too (any-facing furnaces); the rest tune the builder.
        if(mode.equals("blueprint")&&!Set.of("metadataMasks").containsAll(settings.values.keySet()))throw new IllegalArgumentException("construction settings require mode builder");
        int limit=mode.equals("builder")?1048576:16384;
        if(spec.containsKey("size")){List<?> size=list(spec.get("size"));if(size.size()!=3)throw new IllegalArgumentException("size needs three dimensions");for(int i=0;i<3;i++)integer(Map.of("size",size.get(i)),"size",1,1,i==1?256:30000000);}
        List<Map<String,Object>> entries=new ArrayList<>();
        if(spec.containsKey("cells")==spec.containsKey("selection"))throw new IllegalArgumentException("exactly one of cells or selection required");
        if(spec.containsKey("cells")) {
            List<?> cells=list(spec.get("cells"));if(cells.isEmpty()||cells.size()>limit)throw new IllegalArgumentException("cells must contain 1.."+limit+" entries");
            for(Object entry:cells)entries.add(object(entry));
        } else {
            Map<String,Object> sel=object(spec.get("selection"));Bounds bounds=bounds(sel);
            fields(sel,Set.of("min","max","shape","block","replace","axis"));
            if(bounds.volume()>limit)throw new IllegalArgumentException("selection volume exceeds "+limit);
            String shape=string(sel,"shape","fill"),axis=string(sel,"axis","y");if(!Set.of("fill","replace","walls","shell","clear","sphere","hsphere","cylinder","hcylinder").contains(shape))throw new IllegalArgumentException("unknown selection shape");
            if(!Set.of("x","y","z").contains(axis))throw new IllegalArgumentException("invalid cylinder axis");
            if(shape.equals("replace")&&!sel.containsKey("replace"))throw new IllegalArgumentException("replace selector required");
            Map<String,Object> block=shape.equals("clear")?Map.of("clear",true):child(sel,"block");
            for(long i=0;i<bounds.volume();i++) {
                BlockPos p=bounds.at(i);boolean sides=p.getX()==bounds.min.getX()||p.getX()==bounds.max.getX()||p.getZ()==bounds.min.getZ()||p.getZ()==bounds.max.getZ();
                if(shape.equals("walls")&&!sides||shape.equals("shell")&&!sides&&p.getY()!=bounds.min.getY()&&p.getY()!=bounds.max.getY())continue;
                if(Set.of("sphere","hsphere","cylinder","hcylinder").contains(shape)&&!ConstructionMask.contains(shape,axis,p.getX()-bounds.min.getX(),p.getY()-bounds.min.getY(),p.getZ()-bounds.min.getZ(),bounds.max.getX()-bounds.min.getX()+1,bounds.max.getY()-bounds.min.getY()+1,bounds.max.getZ()-bounds.min.getZ()+1))continue;
                Map<String,Object> e=new LinkedHashMap<>(block);e.put("pos",point(p));if(sel.containsKey("replace"))e.put("replace",sel.get("replace"));entries.add(e);
            }
        }
        BlockPos origin=spec.containsKey("origin")?pos(spec.get("origin")):new BlockPos(0,0,0);
        Set<BlockPos> seen=new HashSet<>();List<Cell> cells=new ArrayList<>();
        for(Map<String,Object> e:entries) {
            if(e.containsKey("tileNbt")||e.containsKey("nbt"))throw new IllegalArgumentException("tile state requires an explicit normal-interaction adapter; do not silently discard schematic NBT");
            fields(e,Set.of("pos","id","meta","clear","item","placement","replace","verify"));
            placement(child(e,"placement"));
            verification(child(e,"verify"));
            BlockPos local=pos(e.get("pos"));long x=(long)local.getX()+origin.getX(),y=(long)local.getY()+origin.getY(),z=(long)local.getZ()+origin.getZ();
            if(Math.abs(x)>30000000||y<1||y>254||Math.abs(z)>30000000)throw new IllegalArgumentException("translated cell outside world bounds");
            BlockPos p=new BlockPos((int)x,(int)y,(int)z);if(!seen.add(p))throw new IllegalArgumentException("duplicate cell "+p);
            boolean clear=bool(e,"clear",false);String id=string(e,"id","");if(!clear&&(id.isBlank()||!id.contains(":")))throw new IllegalArgumentException("namespaced block id required");
            if(clear&&!child(e,"verify").isEmpty())throw new IllegalArgumentException("clear cells cannot require a picked item");
            cells.add(new Cell(p,id,integer(e,"meta",0,0,15),clear,child(e,"item"),child(e,"placement"),child(e,"replace"),child(e,"verify")));
        }
        cells.sort(Comparator.comparingInt((Cell c)->c.clear?0:1).thenComparingInt(c->c.clear?-c.pos.getY():c.pos.getY()).thenComparingInt(c->c.pos.getX()).thenComparingInt(c->c.pos.getZ()));
        return List.copyOf(cells);
    }
}
