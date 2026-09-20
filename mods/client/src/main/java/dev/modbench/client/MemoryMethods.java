// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
package dev.modbench.client;

import dev.modbench.api.ControlRegistry;
import com.google.gson.*;
import dev.modbench.bridge.Json;
import dev.modbench.api.WorldMemory;
import dev.modbench.api.WorldMemory.*;
import java.util.*;

/** Small, discoverable memory operations; all writes remain on the game thread. */
final class MemoryMethods {
    static final List<String> METHODS=List.of("context","status","get","waypoint","route","protect","remove","record");
    static String description(String method) {
        return switch(method) {
            case "context" -> "Persistent server world UUID, current dimension and player feet; stable note attachment context";
            case "status" -> "Persistent memory for current server world/dimension: named waypoints, route summaries, protected regions";
            case "get" -> "Read exact saved {kind:waypoint|route|region,name}, including all route points";
            case "waypoint" -> "Save {name,pos:[x,y,z],replace:false}; omitted pos uses current player feet";
            case "route" -> "Save {name,points:[[x,y,z]|waypointName,...],radius:2,replace:false}; 2..4096 ordered anchors, corridor radius 1..16";
            case "protect" -> "Protect inclusive cuboid {name,min:[x,y,z],max:[x,y,z],mode:automation|all_edits}; default automation prevents navigation/bulk edits while targeted work stays normal; changing existing region requires overrideProtection:true; cancels active controls";
            case "remove" -> "Delete {kind:waypoint|route|region,name}; region removal requires overrideProtection:true and cancels active controls";
            case "record" -> "Record walked route {action:start|stop|cancel|status,name,radius:2,replace:false}; stop persists turns; world changes invalidate recording";
            default -> throw new IllegalArgumentException("unknown memory method");
        };
    }
    static Object call(String method,JsonObject params) throws Exception {
        if(method.equals("record")) return ControlRegistry.memory().record(Json.string(params,"action","status"),Json.string(params,"name",""),
            Json.bool(params,"replace",false),Json.number(params,"radius",2,1,16));
        WorldMemory memory=ControlRegistry.memory().memory();String name=Json.string(params,"name","");
        switch(method) {
            case "context": return ControlRegistry.memory().context();
            case "status": return ControlRegistry.memory().status();
            case "get": {
                String kind=Json.string(params,"kind","");
                Object result=switch(kind) {
                    case "waypoint" -> memory.snapshot().waypoints().get(name);
                    case "route" -> memory.snapshot().routes().get(name);
                    case "region" -> memory.snapshot().regions().get(name);
                    default -> throw new IllegalArgumentException("kind must be waypoint, route or region");
                };
                if(result==null) throw new IllegalArgumentException("unknown named "+kind);
                return result;
            }
            case "waypoint": memory.waypoint(name,params.has("pos")?pos(params.get("pos")):ControlRegistry.memory().feet(),Json.bool(params,"replace",false));break;
            case "route": {
                if(!params.has("points")||!params.get("points").isJsonArray()) throw new IllegalArgumentException("points array required");
                List<Pos> points=new ArrayList<>();
                for(JsonElement point:params.getAsJsonArray("points")) {
                    if(point.isJsonPrimitive()&&point.getAsJsonPrimitive().isString()) {
                        Pos saved=memory.snapshot().waypoints().get(point.getAsString());
                        if(saved==null) throw new IllegalArgumentException("unknown waypoint: "+point.getAsString());
                        points.add(saved);
                    } else points.add(pos(point));
                }
                memory.route(new Route(name,points,Json.number(params,"radius",2,1,16)),Json.bool(params,"replace",false));break;
            }
            case "protect": memory.protect(new Region(name,pos(params.get("min")),pos(params.get("max")),Json.string(params,"mode","automation")),Json.bool(params,"overrideProtection",false));break;
            case "remove": memory.remove(Json.string(params,"kind",""),name,Json.bool(params,"overrideProtection",false));break;
            default: throw new IllegalArgumentException("unknown memory method");
        }
        return Json.object("saved",true,"name",name,"revision",memory.snapshot().revision(),"scope",memory.scope());
    }
    private static Pos pos(JsonElement value) {
        try {
            if(value==null||!value.isJsonArray()||value.getAsJsonArray().size()!=3) throw new IllegalArgumentException();
            JsonArray p=value.getAsJsonArray();
            return new Pos(p.get(0).getAsBigDecimal().intValueExact(),p.get(1).getAsBigDecimal().intValueExact(),p.get(2).getAsBigDecimal().intValueExact());
        } catch(RuntimeException error) {throw new IllegalArgumentException("position must be [integer x,y,z] within world bounds");}
    }
}
