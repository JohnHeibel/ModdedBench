// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.client;

import com.google.gson.*;
import dev.modbench.bridge.*;
import net.minecraft.client.Minecraft;
import java.util.Set;
import java.util.function.*;

/** Mixed batches preserve separate client/server tick provenance, never claim cross-side atomicity. */
final class ClientObservations {
    static final Set<String> METHODS=Set.of("obs.tile","obs.nbt","obs.waila");
    private final ClientRuntime runtime;
    private final Supplier<JsonObject> context;
    private final BiFunction<Request,Object,JsonObject> local;
    ClientObservations(ClientRuntime runtime,Supplier<JsonObject> context,BiFunction<Request,Object,JsonObject> local){this.runtime=runtime;this.context=context;this.local=local;}
    Object call(Request r) {
        JsonObject params=normalize(r.method,r.params),queries=Json.object("value",Json.object("method",r.method,"params",params));
        send(r,queries,result->{
            JsonObject errors=result.getAsJsonObject("errors");if(errors.has("value")){r.fail("observation_failed",errors.get("value").toString());return;}
            JsonObject value=result.getAsJsonObject("values").getAsJsonObject("value");r.reply(finish(r.method,value,params));
        });return null;
    }
    Object batch(Request r) {
        JsonObject queries=r.params.getAsJsonObject("queries");if(queries==null||queries.entrySet().size()>16)throw new IllegalArgumentException("queries must contain at most 16 observations");
        JsonObject nearby=new JsonObject(),remote=new JsonObject();
        for(var entry:queries.entrySet()) {
            JsonObject q=entry.getValue().getAsJsonObject();String method=Json.string(q,"method","");
            if(METHODS.contains(method))remote.add(entry.getKey(),Json.object("method",method,"params",normalize(method,q.has("params")?q.getAsJsonObject("params"):new JsonObject())));
            else nearby.add(entry.getKey(),q);
        }
        JsonObject before=context.get();
        Request child=new Request(r.id,r.method,Json.object("queries",nearby),r.session,runtime,ignored->{});
        JsonObject result=local.apply(child,before);
        if(remote.entrySet().isEmpty())return result;
        send(r,remote,reply->{
            if(!before.equals(context.get())){r.fail("stale_context","world changed during observation batch");return;}
            JsonObject values=reply.getAsJsonObject("values");
            for(var entry:values.entrySet())try{JsonObject q=remote.getAsJsonObject(entry.getKey());result.getAsJsonObject("values").add(entry.getKey(),finish(q.get("method").getAsString(),entry.getValue().getAsJsonObject(),q.getAsJsonObject("params")));}
            catch(Exception|LinkageError e){result.getAsJsonObject("errors").add(entry.getKey(),Json.object("code","observation_failed","msg",e.toString()));}
            for(var e:reply.getAsJsonObject("errors").entrySet())result.getAsJsonObject("errors").add(e.getKey(),e.getValue());
            result.add("serverTick",reply.get("serverTick"));result.addProperty("consistency","server reads share one server tick; client reads share the earlier client tick; sides are not atomic together");r.reply(result);
        });return null;
    }
    private void send(Request r,JsonObject queries,Consumer<JsonObject> finish) {
        var mc=Minecraft.getMinecraft();if(mc.thePlayer==null||mc.theWorld==null)throw new IllegalArgumentException("player must be in world");
        var state=runtime.clock.status().getAsJsonObject("state");
        runtime.clock.observe(r,Json.object("queries",queries,"worldId",state.get("worldId"),"dimension",mc.thePlayer.dimension),finish);
    }
    private JsonObject normalize(String method,JsonObject original) {
        JsonObject params=Json.GSON.fromJson(original.toString(),JsonObject.class);
        if(method.equals("obs.nbt"))return params;
        var mc=Minecraft.getMinecraft();if(mc.theWorld==null||mc.thePlayer==null)throw new IllegalArgumentException("player must be in world");
        if(!params.has("pos")&&!params.has("x")) {
            dev.modbench.api.ControlRegistry.targeting().refresh();var hit=mc.objectMouseOver;
            if(hit==null||hit.typeOfHit!=net.minecraft.util.MovingObjectPosition.MovingObjectType.BLOCK)throw new IllegalArgumentException("block position or block crosshair required");
            params.add("pos",Json.array(hit.blockX,hit.blockY,hit.blockZ));params.addProperty("side",hit.sideHit);
        }
        return params;
    }
    private JsonObject finish(String method,JsonObject value,JsonObject params) {
        if(value.has("waila")) {
            JsonObject overlay=WailaObservation.read(value,params,value.remove("waila").getAsJsonObject());
            if(!method.equals("obs.tile")){overlay.add("pos",value.get("pos"));overlay.add("provenance",value.get("provenance"));overlay.addProperty("src","server_data_client_providers");return overlay;}
            value.add("hwyla",overlay);
        }
        return value;
    }
}
