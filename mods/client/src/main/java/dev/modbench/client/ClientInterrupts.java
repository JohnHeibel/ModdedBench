// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.client;

import dev.modbench.api.ControlRegistry;
import com.google.gson.*;
import dev.modbench.bridge.*;
import java.util.*;
import net.minecraft.client.Minecraft;

/** Reaction receipts and an admission latch. Predicates live in the editable external supervisor. */
final class ClientInterrupts {
    private final ClientRuntime runtime;
    private final LinkedHashMap<String,JsonObject> receipts=new LinkedHashMap<>();
    private final Set<String> latched=new LinkedHashSet<>();
    ClientInterrupts(ClientRuntime runtime) {this.runtime=runtime;}
    JsonObject context() {
        var mc=Minecraft.getMinecraft();var state=runtime.clock.status().getAsJsonObject("state");
        return Json.object("worldId",state.get("worldId"),"dimension",mc.theWorld==null?null:mc.theWorld.provider.dimensionId,
            "bridgeId",runtime.bridgeId(),"worldEpoch",runtime.worldEpoch());
    }
    JsonObject status(JsonObject params) {
        var owner=ControlRegistry.controls().arbiter().current();JsonArray selected=new JsonArray();
        if(params.has("eventId")) {
            JsonObject receipt=receipts.get(Json.string(params,"eventId",""));
            if(receipt==null)throw new IllegalArgumentException("unknown eventId");selected.add(receipt);
        } else {
            int limit=Json.integer(params,"limit",0,0,32),skip=Math.max(0,receipts.size()-limit),index=0;
            for(JsonObject receipt:receipts.values())if(index++>=skip)selected.add(receipt);
        }
        return Json.object("context",context(),"operationId",owner.operationId(),"owner",owner.label(),"latched",latched,"receiptCount",receipts.size(),"receipts",selected);
    }
    void admit(Request r) {
        if(latched.isEmpty()||runtime.readOnly(r.method)) return;
        String m=r.method;
        if(m.equals("act.stop")||m.startsWith("interrupt.")||m.equals("sys.shutdown")||m.equals("sys.disconnect")||m.equals("time.pause")||m.equals("time.configure")) return;
        if(m.startsWith("act.")&&!m.equals("act.status") || m.equals("time.resume") || m.equals("nei.view") || m.equals("nei.inspect") ||
            m.startsWith("gui.")&&!Set.of("gui.status","gui.hit_test").contains(m) ||
            m.startsWith("quest.")||m.startsWith("nav."))
            throw new IllegalArgumentException("interrupt_latched: acknowledge "+latched+" before starting another action");
    }
    Object ack(Request r) {
        String id=Json.string(r.params,"eventId","");
        if(!receipts.containsKey(id)) throw new IllegalArgumentException("unknown eventId");
        boolean removed=latched.remove(id);return Json.object("acknowledged",removed,"eventId",id,"latched",latched,"resumed",false);
    }
    Object fire(Request r) {
        String id=UUID.fromString(Json.string(r.params,"eventId","")).toString();
        if(receipts.containsKey(id)) return receipts.get(id);
        if(!context().equals(r.params.get("expectedContext"))) throw new IllegalArgumentException("stale_context: bridge/world changed");
        if(r.params.has("expectedOperationId")&&r.params.get("expectedOperationId").getAsLong()!=ControlRegistry.controls().arbiter().current().operationId()) throw new IllegalArgumentException("stale_operation: control owner changed");
        String reason=Json.string(r.params,"reason","interrupt");
        if(reason.isBlank()||reason.length()>240) throw new IllegalArgumentException("reason must contain 1..240 characters");
        JsonArray effects=r.params.getAsJsonArray("effects");
        if(effects==null||effects.size()==0||effects.size()>3) throw new IllegalArgumentException("nonempty effects array required (notify, cancel, pause)");
        Set<String> selected=new LinkedHashSet<>();for(JsonElement e:effects) {
            String effect=e.getAsString();if(!Set.of("notify","cancel","pause").contains(effect)) throw new IllegalArgumentException("unknown effect: "+effect);selected.add(effect);
        }
        if(r.params.has("payload")&&r.params.get("payload").toString().length()>65536) throw new IllegalArgumentException("payload too large");
        if(receipts.size()>=256) {
            String removable=receipts.keySet().stream().filter(key->!latched.contains(key)).findFirst().orElse(null);
            if(removable==null) throw new IllegalArgumentException("acknowledge outstanding interrupts before adding more");
            receipts.remove(removable);
        }
        JsonObject receipt=Json.object("eventId",id,"reason",reason,"context",context(),"tick",runtime.tick(),"effects",selected,
            "payload",r.params.get("payload"),"cancelled",false,"pauseConfirmed",false);
        receipts.put(id,receipt);
        if(Json.bool(r.params,"latch",selected.contains("cancel")||selected.contains("pause"))) latched.add(id);
        receipt.addProperty("latched",latched.contains(id));
        if(selected.contains("cancel")) {runtime.interruptControls();receipt.addProperty("cancelled",true);}
        if(selected.contains("pause")) {
            // Use the established coordinated clock; acknowledgement includes settled worker/client state.
            try {runtime.clock.interruptPause(r,receipt,reason);return null;}
            catch(Exception e) {receipt.addProperty("pauseError",e.toString());}
        }
        return receipt;
    }
}
