// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
package dev.modbench.server;

import com.google.gson.*;
import dev.modbench.bridge.Json;
import net.minecraft.nbt.*;
import java.util.*;

/** DJ2 Nbt's bounded summaries and drill-down, adapted to 1.7 native tags.
 * Snapshots are immutable, uniquely named even during a pause, and owner/world scoped.
 */
final class NbtSnapshots {
    private record Held(NBTTagCompound tag, String owner, String world, JsonObject provenance, long created, int bytes) {}
    private final LinkedHashMap<String,Held> held=new LinkedHashMap<>();
    private int bytes;
    static final int MAX_TAG_BYTES=1024*1024, MAX_TOTAL_BYTES=8*1024*1024;
    String remember(NBTTagCompound tag,String owner,String world,JsonObject provenance) throws java.io.IOException {
        // Bound the serialized allocation, rather than allowing a huge mod tag to
        // exhaust the observation cache. Native writeToNBT itself is mod-owned.
        class Limited extends java.io.OutputStream {int size;public void write(int b) throws java.io.IOException {if(++size>MAX_TAG_BYTES)throw new java.io.IOException("NBT snapshot exceeds 1MiB");}public void write(byte[] b,int off,int len)throws java.io.IOException{size+=len;if(size>MAX_TAG_BYTES)throw new java.io.IOException("NBT snapshot exceeds 1MiB");}}
        Limited sink=new Limited();CompressedStreamTools.write(tag,new java.io.DataOutputStream(sink));
        evict();while(!held.isEmpty()&&(held.size()>=64||bytes+sink.size>MAX_TOTAL_BYTES)) removeFirst();
        String id="nbt:"+UUID.randomUUID();held.put(id,new Held((NBTTagCompound)tag.copy(),owner,world,Json.GSON.fromJson(provenance.toString(),JsonObject.class),System.nanoTime(),sink.size));bytes+=sink.size;return id;
    }
    private void removeFirst(){var it=held.entrySet().iterator();var e=it.next();bytes-=e.getValue().bytes;it.remove();}
    private void evict(){long now=System.nanoTime();while(!held.isEmpty()&&now-held.values().iterator().next().created>600_000_000_000L)removeFirst();}
    JsonObject read(JsonObject params,String owner,String world) {
        evict();String handle=Json.string(params,"handle","");Held h=held.get(handle);
        if(h==null||!h.owner.equals(owner)||!h.world.equals(world))throw new IllegalArgumentException("unknown, expired or different-world NBT handle; re-observe the tile");
        JsonElement path=params.has("path")?params.get("path"):new JsonPrimitive("");
        NBTBase tag=resolve(h.tag,path);
        JsonObject out=describe(tag,params);out.addProperty("handle",handle);out.add("path",path);out.add("provenance",h.provenance);out.addProperty("snapshot",true);return out;
    }
    static NBTBase resolve(NBTBase root,JsonElement path) {
        List<String> parts=new ArrayList<>();
        if(path.isJsonArray()) for(JsonElement part:path.getAsJsonArray())parts.add(part.getAsString());
        else {String p=path.getAsString();if(!p.isEmpty())parts.addAll(Arrays.asList(p.split("[./]",-1)));}
        NBTBase current=root;
        for(String part:parts) {
            if(current instanceof NBTTagCompound c) {if(!c.hasKey(part))throw new IllegalArgumentException("NBT key not found: "+part);current=c.getTag(part);}
            else {int index;try{index=Integer.parseInt(part);}catch(NumberFormatException e){throw new IllegalArgumentException("NBT array/list index required: "+part);}
                int size=length(current);if(index<0||index>=size)throw new IllegalArgumentException("NBT index outside 0.."+(size-1));current=element(current,index);}
        }
        return current;
    }
    static JsonObject describe(NBTBase tag,JsonObject params) {
        int budget=Json.integer(params,"budget",Json.string(params,"detail","summary").equals("full")?16384:4096,256,65536);
        int depth=Json.integer(params,"depth",8,0,32),offset=Json.integer(params,"offset",0,0,Integer.MAX_VALUE),limit=Json.integer(params,"limit",64,1,256);
        Budget b=new Budget(budget-192);JsonElement value=value(tag,b,depth,offset,limit);
        JsonObject out=Json.object("type",type(tag),"value",value,"truncated",b.truncated,"budget",budget);
        if(tag instanceof NBTTagCompound||tag instanceof NBTTagList||tag instanceof NBTTagByteArray||tag instanceof NBTTagIntArray) {
            int count=length(tag);out.addProperty("size",count);out.addProperty("offset",offset);out.addProperty("limit",limit);
            // On budget exhaustion, drill into keys/indices or increase budget;
            // offset pagination must never claim an unreturned child was read.
            out.addProperty("paging","offset/limit apply to this root; use path for nested values");
        }
        return out;
    }
    private static class Budget {int left;boolean truncated;Budget(int n){left=n;}boolean take(int n){if(n>left){truncated=true;return false;}left-=n;return true;}}
    private static JsonElement elided(NBTBase tag,Budget b){b.truncated=true;return Json.object("_elided",true,"_type",type(tag),"_size",length(tag));}
    private static JsonElement value(NBTBase tag,Budget b,int depth,int offset,int limit) {
        boolean collection=tag instanceof NBTTagCompound||tag instanceof NBTTagList||tag instanceof NBTTagByteArray||tag instanceof NBTTagIntArray;
        if(collection) {
            if(depth<=0||b.left<96) return elided(tag,b);
            b.take(96);int size=length(tag),end=(int)Math.min(size,(long)offset+limit);
            if(offset>0||end<size)b.truncated=true;
            if(tag instanceof NBTTagCompound c){JsonObject out=new JsonObject();List<String> keys=new ArrayList<>();for(Object k:c.func_150296_c())keys.add((String)k);Collections.sort(keys);
                for(int i=offset;i<end;i++){String k=keys.get(i);if(!b.take(new JsonPrimitive(k).toString().length()*3+96))break;out.add(k,value(c.getTag(k),b,depth-1,0,limit));}return out;}
            JsonArray out=new JsonArray();for(int i=offset;i<end;i++){if(!b.take(96))break;out.add(value(element(tag,i),b,depth-1,0,limit));}return out;
        }
        JsonElement out;
        if(tag instanceof NBTBase.NBTPrimitive n)out=tag.getId()<=4?new JsonPrimitive(n.func_150291_c()):new JsonPrimitive(n.func_150286_g());
        else if(tag instanceof NBTTagString s)out=new JsonPrimitive(s.func_150285_a_());
        else out=new JsonPrimitive(tag.toString());
        if(!b.take(out.toString().length()*3+8))return elided(tag,b);return out;
    }
    static int length(NBTBase tag){if(tag instanceof NBTTagCompound c)return c.func_150296_c().size();if(tag instanceof NBTTagList l)return l.tagCount();if(tag instanceof NBTTagByteArray a)return a.func_150292_c().length;if(tag instanceof NBTTagIntArray a)return a.func_150302_c().length;return 1;}
    // 1.7 has no generic list getter. Read the native backing list without
    // removeTag (which mutates snapshots) or repeatedly copying entire lists.
    private static final java.lang.reflect.Field LIST=cpw.mods.fml.relauncher.ReflectionHelper.findField(NBTTagList.class,"tagList","field_74747_a");
    static NBTBase element(NBTBase tag,int index){if(tag instanceof NBTTagList l)try{return (NBTBase)((java.util.List<?>)LIST.get(l)).get(index);}catch(IllegalAccessException e){throw new IllegalStateException(e);}if(tag instanceof NBTTagByteArray a)return new NBTTagByte(a.func_150292_c()[index]);if(tag instanceof NBTTagIntArray a)return new NBTTagInt(a.func_150302_c()[index]);throw new IllegalArgumentException("cannot index scalar NBT");}
    static String type(NBTBase tag){return new String[]{"end","byte","short","int","long","float","double","byte[]","string","list","compound","int[]"}[tag.getId()];}
}
