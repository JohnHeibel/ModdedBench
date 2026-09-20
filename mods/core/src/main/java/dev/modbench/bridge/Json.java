// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public final class Json {
    public static final Gson GSON = new Gson();
    private Json() {}

    public static JsonObject object(Object... pairs) {
        JsonObject out = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) {
            out.add((String) pairs[i], GSON.toJsonTree(pairs[i + 1]));
        }
        return out;
    }

    public static JsonArray array(Object... values) {
        JsonArray out = new JsonArray();
        for (Object value : values) out.add(GSON.toJsonTree(value));
        return out;
    }

    public static int integer(JsonObject p, String name, int fallback, int min, int max) {
        if (!p.has(name)) return fallback;
        JsonElement e = p.get(name);
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        double n = e.getAsDouble();
        if (!Double.isFinite(n) || n != Math.rint(n) || n < min || n > max) {
            throw new IllegalArgumentException(name + " must be in " + min + ".." + max);
        }
        return (int) n;
    }

    public static double number(JsonObject p, String name, double fallback, double min, double max) {
        if (!p.has(name)) return fallback;
        JsonElement e = p.get(name);
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException(name + " must be numeric");
        double n = e.getAsDouble();
        if (!Double.isFinite(n) || n < min || n > max) throw new IllegalArgumentException(name + " out of range");
        return n;
    }

    public static String string(JsonObject p, String name, String fallback) {
        if (!p.has(name)) return fallback;
        JsonElement e = p.get(name);
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) throw new IllegalArgumentException(name + " must be a string");
        return e.getAsString();
    }
    public static boolean bool(JsonObject p,String name,boolean fallback) {
        if(!p.has(name)) return fallback;
        JsonElement e=p.get(name);
        if(!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException(name+" must be a boolean");
        return e.getAsBoolean();
    }
}
