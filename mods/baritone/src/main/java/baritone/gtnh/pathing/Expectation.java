// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package baritone.gtnh.pathing;

import baritone.compat.BlockPos;
import java.util.*;
import static baritone.gtnh.pathing.WorkSpec.*;

/**
 * What a step should have changed, as the caller reads it: one obs.* read, a path into its result and a comparison.
 * The builder runs it and compares; it never interprets the value. faces marks the compared value as a face or a list
 * of faces, so that a rotated plan can turn it; the builder itself ignores it.
 */
public record Expectation(String method,Map<String,Object> params,BlockPos pos,String path,String op,Object value,boolean faces) {
    public static final Object MISSING=new Object(){@Override public String toString(){return "missing";}};
    public static Expectation parse(Map<String,Object> e,BlockPos origin) {
        fields(e,Set.of("method","params","pos","path","equals","contains","changed","faces"));
        String method=string(e,"method","");
        if(!method.matches("obs\\.[a-z_]+"))throw new IllegalArgumentException("expect.method is an obs.* read");
        Map<String,Object> params=e.containsKey("params")?child(e,"params"):Map.of();
        BlockPos pos=null;
        if(e.containsKey("pos")){BlockPos l=WorkSpec.pos(e.get("pos"));pos=new BlockPos(l.getX()+origin.getX(),l.getY()+origin.getY(),l.getZ()+origin.getZ());}
        String path=string(e,"path","");
        List<String> ops=List.of("equals","contains","changed").stream().filter(e::containsKey).toList();
        if(ops.size()!=1)throw new IllegalArgumentException("expect needs exactly one of equals, contains, changed");
        String op=ops.get(0);Object value=e.get(op);
        if(op.equals("changed")&&!Boolean.TRUE.equals(value))throw new IllegalArgumentException("expect.changed is true");
        segments(path);
        return new Expectation(method,params,pos,path,op,value,bool(e,"faces",false));
    }
    /** The read's parameters, with pos (relative to the plan origin) made absolute. */
    public Map<String,Object> request() {
        if(pos==null)return params;
        Map<String,Object> out=new LinkedHashMap<>(params);out.put("pos",List.of(pos.getX(),pos.getY(),pos.getZ()));return out;
    }
    public Map<String,Object> json(BlockPos origin) {
        Map<String,Object> out=new LinkedHashMap<>();out.put("method",method);if(!params.isEmpty())out.put("params",params);
        if(pos!=null)out.put("pos",List.of(pos.getX()-origin.getX(),pos.getY()-origin.getY(),pos.getZ()-origin.getZ()));
        if(!path.isEmpty())out.put("path",path);out.put(op,value);if(faces)out.put("faces",true);return out;
    }
    static List<String> segments(String path) {
        if(path.isEmpty())return List.of();
        String p=path.replaceAll("\\[(\\d+)\\]",".$1");if(p.startsWith("."))p=p.substring(1);
        List<String> out=List.of(p.split("\\.",-1));
        if(out.stream().anyMatch(String::isEmpty)||out.size()>16)throw new IllegalArgumentException("expect.path is keys and indices: a.b[0].c");
        return out;
    }
    /** The value at path in a JSON-shaped result (maps, lists, numbers, strings), or MISSING. */
    public static Object extract(Object root,String path) {
        Object at=root;
        for(String key:segments(path)) {
            if(at instanceof Map<?,?> m){if(!m.containsKey(key))return MISSING;at=m.get(key);}
            else if(at instanceof List<?> l&&key.matches("\\d+")){int i=Integer.parseInt(key);if(i>=l.size())return MISSING;at=l.get(i);}
            else return MISSING;
        }
        return at;
    }
    /** JSON equality: numbers by value, maps and lists by content. */
    public static boolean same(Object a,Object b) {
        if(a instanceof Number x&&b instanceof Number y)return Double.compare(x.doubleValue(),y.doubleValue())==0;
        if(a instanceof Map<?,?> x&&b instanceof Map<?,?> y){
            if(!x.keySet().equals(y.keySet()))return false;
            for(var k:x.keySet())if(!same(x.get(k),y.get(k)))return false;return true;
        }
        if(a instanceof List<?> x&&b instanceof List<?> y){
            if(x.size()!=y.size())return false;for(int i=0;i<x.size();i++)if(!same(x.get(i),y.get(i)))return false;return true;
        }
        return Objects.equals(a,b);
    }
    /** Does the value read after the step (and before it, for changed) meet the expectation? */
    public boolean met(Object before,Object after) {
        if(after==MISSING)return false;
        return switch(op) {
            case "equals"->same(after,value);
            case "contains"->{
                if(after instanceof String s&&value instanceof String v)yield s.contains(v);
                if(!(after instanceof List<?> l))yield false;
                List<?> wanted=value instanceof List<?> w?w:List.of(value);
                yield wanted.stream().allMatch(w->l.stream().anyMatch(x->same(x,w)));
            }
            default->!same(before,after);
        };
    }
}
