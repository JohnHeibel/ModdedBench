// SPDX-License-Identifier: MIT
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.client;

import com.google.gson.*;
import dev.modbench.bridge.Json;
import net.minecraft.client.Minecraft;
import net.minecraft.util.MovingObjectPosition;
import java.lang.reflect.*;
import java.util.*;

/** The running client's own objects, read by reflection: a path of field names from a root, rendered as JSON. It reads fields and
 * never sets one or calls a method (toString only on JDK values and map keys). It sees the client's copy, which the server may
 * never have filled in. */
final class LiveProbe {
    static final int MAX_NODES=4000,MAX_TEXT=400;
    /** A class as a root: its static fields. */
    record Statics(Class<?> type) {}
    private final int limit;private int nodes;
    LiveProbe(int limit){this.limit=limit;}

    static JsonObject call(JsonObject p) throws Exception {
        Object at=walk(root(p),p.has("path")?p.getAsJsonArray("path"):new JsonArray());
        LiveProbe probe=new LiveProbe(Json.integer(p,"limit",32,1,256));
        JsonObject out=Json.object("class",at==null?null:name(at),"value",probe.render(at,Json.integer(p,"depth",2,0,5)));
        if(probe.nodes>MAX_NODES) out.addProperty("truncated",true);
        return out;
    }

    private static Object root(JsonObject p) throws Exception {
        Minecraft mc=Minecraft.getMinecraft();String from=Json.string(p,"from","looking");
        if(from.equals("mc")) return mc;
        if(from.equals("screen")) return need(mc.currentScreen,"no screen is open");
        if(from.equals("class")) return new Statics(Class.forName(Json.string(p,"name",""),false,LiveProbe.class.getClassLoader()));
        need(mc.theWorld,"player must be in world");
        switch(from) {
            case "player": return mc.thePlayer;
            case "world": return mc.theWorld;
            case "entity": return need(mc.theWorld.getEntityByID(Json.integer(p,"entityId",0,Integer.MIN_VALUE,Integer.MAX_VALUE)),"no loaded entity with that entityId");
            case "tile": {
                JsonArray a=p.getAsJsonArray("pos");if(a==null||a.size()!=3) throw new IllegalArgumentException("tile needs pos:[x,y,z]");
                return need(mc.theWorld.getTileEntity(a.get(0).getAsInt(),a.get(1).getAsInt(),a.get(2).getAsInt()),"no tile entity at "+a+" in the client's world");
            }
            case "looking": {
                dev.modbench.api.ControlRegistry.targeting().refresh();MovingObjectPosition hit=mc.objectMouseOver;
                if(hit==null||hit.typeOfHit==MovingObjectPosition.MovingObjectType.MISS) throw new IllegalArgumentException("the crosshair is on nothing");
                if(hit.typeOfHit==MovingObjectPosition.MovingObjectType.ENTITY) return hit.entityHit;
                Object tile=mc.theWorld.getTileEntity(hit.blockX,hit.blockY,hit.blockZ);
                return tile!=null?tile:mc.theWorld.getBlock(hit.blockX,hit.blockY,hit.blockZ);
            }
            default: throw new IllegalArgumentException("from must be looking, tile, entity, screen, player, world, mc or class");
        }
    }
    private static Object need(Object o,String why){if(o==null) throw new IllegalArgumentException(why);return o;}

    /** Each step is a field name, an index into an array, list or set, a map key as text, or an array of alternatives. */
    static Object walk(Object at,JsonArray path) throws Exception {
        for(JsonElement step:path) {
            List<String> names=new ArrayList<>();
            if(step.isJsonArray()) for(JsonElement e:step.getAsJsonArray()) names.add(e.getAsString()); else names.add(step.getAsString());
            if(at==null) throw new IllegalArgumentException("null before "+names.get(0));
            if(at.getClass().isArray()) at=Array.get(at,index(names.get(0),Array.getLength(at)));
            else if(at instanceof List<?> list) at=list.get(index(names.get(0),list.size()));
            else if(at instanceof Map<?,?> map) at=entry(map,names);
            else if(at instanceof Collection<?> set) {Iterator<?> it=set.iterator();for(int i=index(names.get(0),set.size());i>0;i--) it.next();at=it.next();}
            else at=field(at,names);
        }
        return at;
    }
    private static Object entry(Map<?,?> map,List<String> names) {
        for(var e:map.entrySet()) if(names.contains(String.valueOf(e.getKey()))) return e.getValue();
        throw new IllegalArgumentException("no key "+names+" among "+map.size()+" keys");
    }
    private static Object field(Object at,List<String> names) throws Exception {
        boolean statics=at instanceof Statics;Class<?> type=statics?((Statics)at).type():at.getClass();
        List<Field> fields=fields(type,statics);
        for(Field f:fields) if(names.contains(f.getName())) return read(f,statics?null:at);
        List<String> have=new ArrayList<>();for(Field f:fields) have.add(f.getName());
        throw new IllegalArgumentException("no field "+names+" on "+type.getName()+"; it has "+have);
    }
    private static int index(String step,int size) {
        int i;try{i=Integer.parseInt(step);}catch(NumberFormatException e){throw new IllegalArgumentException("a list, array or set takes an index, not "+step);}
        if(i<0||i>=size) throw new IllegalArgumentException("index "+i+" outside 0.."+(size-1));
        return i;
    }
    private static List<Field> fields(Class<?> type,boolean statics) {
        List<Field> out=new ArrayList<>();
        for(Class<?> c=type;c!=null&&!jdk(c);c=c.getSuperclass())
            for(Field f:c.getDeclaredFields()) if(!f.isSynthetic()&&(!statics||Modifier.isStatic(f.getModifiers()))) out.add(f);
        return out;
    }
    private static Object read(Field f,Object at) throws Exception {f.setAccessible(true);return f.get(at);}
    private static boolean jdk(Class<?> c){String n=c.getName();return n.startsWith("java.")||n.startsWith("javax.")||n.startsWith("jdk.")||n.startsWith("sun.")||n.startsWith("com.sun.");}
    private static String name(Object o){return o instanceof Statics s?"static "+s.type().getName():o.getClass().getName();}
    private static String clip(String s){return s.length()>MAX_TEXT?s.substring(0,MAX_TEXT)+"...":s;}

    /** depth is how many levels of objects open; an unopened object shows only its class, and a longer path opens it. */
    JsonElement render(Object o,int depth){return render(o,depth,new IdentityHashMap<>());}
    private JsonElement render(Object o,int depth,IdentityHashMap<Object,Boolean> open) {
        if(o==null) return JsonNull.INSTANCE;
        if(++nodes>MAX_NODES) return new JsonPrimitive("...");
        if(o instanceof Double d&&!Double.isFinite(d)||o instanceof Float f&&!Float.isFinite(f)) return new JsonPrimitive(o.toString());
        if(o instanceof Number n) return new JsonPrimitive(n);
        if(o instanceof Boolean b) return new JsonPrimitive(b);
        if(o instanceof Character||o instanceof CharSequence) return new JsonPrimitive(clip(o.toString()));
        if(o instanceof Enum<?> e) return new JsonPrimitive(e.name());
        if(o instanceof Class<?> c) return new JsonPrimitive(c.getName());
        boolean many=o.getClass().isArray()||o instanceof Collection||o instanceof Map;
        if(!many&&!(o instanceof Statics)&&jdk(o.getClass())) return new JsonPrimitive(clip(String.valueOf(o)));
        if(depth<=0||open.containsKey(o)) return Json.object("@class",name(o));
        open.put(o,true);
        try {
            if(o instanceof Map<?,?> map) {
                JsonObject out=new JsonObject();int i=0;
                for(var e:map.entrySet()) {if(i++==limit) break;out.add(clip(String.valueOf(e.getKey())),render(e.getValue(),depth-1,open));}
                if(map.size()>limit) out.addProperty("...",(map.size()-limit)+" more");
                return out;
            }
            if(many) {
                JsonArray out=new JsonArray();int size;
                if(o instanceof Collection<?> c) {size=c.size();int i=0;for(Object x:c) {if(i++==limit) break;out.add(render(x,depth-1,open));}}
                else {size=Array.getLength(o);for(int i=0;i<Math.min(size,limit);i++) out.add(render(Array.get(o,i),depth-1,open));}
                if(size>limit) out.add(new JsonPrimitive((size-limit)+" more"));
                return out;
            }
            boolean statics=o instanceof Statics;JsonObject out=Json.object("@class",name(o));
            for(Field f:fields(statics?((Statics)o).type():o.getClass(),statics)) {
                if(!statics&&Modifier.isStatic(f.getModifiers())) continue;
                try {out.add(f.getName(),render(read(f,statics?null:o),depth-1,open));}
                catch(Exception|LinkageError e) {out.addProperty(f.getName(),"<unreadable: "+e.getClass().getSimpleName()+">");}
            }
            return out;
        } finally {open.remove(o);}
    }
}
