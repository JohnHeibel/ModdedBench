// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.client;

import com.google.gson.JsonObject;
import dev.modbench.api.UiInput;
import dev.modbench.bridge.Json;
import java.util.function.Supplier;
import org.lwjgl.input.Keyboard;

/** Holds native tooltip modifiers only for the synchronous observation. */
final class TooltipModifiers {
    static <T> T read(JsonObject params,Supplier<T> observation) {
        int[] keys={Keyboard.KEY_LSHIFT,Keyboard.KEY_LCONTROL,Keyboard.KEY_LMENU};
        String[] names={"shift","ctrl","alt"};
        boolean[] previous=new boolean[keys.length];
        for(int i=0;i<keys.length;i++) previous[i]=UiInput.keyDown(keys[i]);
        try {
            for(int i=0;i<keys.length;i++) if(params.has(names[i])) UiInput.modifier(keys[i],Json.bool(params,names[i],false));
            return observation.get();
        } finally {
            for(int i=0;i<keys.length;i++) UiInput.modifier(keys[i],previous[i]);
        }
    }
}
