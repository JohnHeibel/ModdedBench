// SPDX-License-Identifier: MIT
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.modbench.bridge.Json;
import java.util.Arrays;

/** act.input attackTarget [x,y,z]: the block a raw attack's lock must start on, checked against the lock itself. */
final class AttackTarget {
    static int[] parse(JsonObject p,boolean attack) {
        if(!p.has("attackTarget")) return null;
        if(!attack||Json.bool(p,"allowRetarget",false)) throw new IllegalArgumentException("attackTarget needs attack in keys and no allowRetarget");
        JsonElement e=p.get("attackTarget");
        JsonArray a=e.isJsonArray()?e.getAsJsonArray():null;
        if(a==null||a.size()!=3) throw new IllegalArgumentException("attackTarget is [x,y,z]");
        int[] out=new int[3];
        for(int i=0;i<3;i++) {
            if(!a.get(i).isJsonPrimitive()||!a.get(i).getAsJsonPrimitive().isNumber()||a.get(i).getAsDouble()!=a.get(i).getAsInt()) throw new IllegalArgumentException("attackTarget is [x,y,z] integers");
            out[i]=a.get(i).getAsInt();
        }
        return out;
    }
    /** The refusal receipt when the lock is on another block or none; null when it is on the target. */
    static JsonObject refusal(int[] target,int[] locked) {
        if(target==null||Arrays.equals(target,locked)) return null;
        return Json.object("refused",Json.object("reason","attack_target_mismatch","expected",target,"actual",locked==null?"no block under the crosshair":locked),"actionSent",false);
    }
}
