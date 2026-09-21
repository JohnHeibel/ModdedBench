// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.client;

import java.util.LinkedHashSet;
import java.util.Set;

/** Let native movement and its pose packet precede a modified mouse press. */
final class InputChord {
    private final Set<Integer> keys, initial;
    private int warmup, remaining;

    InputChord(Set<Integer> keys, int ticks, int sneak, int attack, int use) {
        this.keys=Set.copyOf(keys);
        remaining=ticks;
        Set<Integer> before=new LinkedHashSet<>(keys);
        if(keys.contains(sneak) && (keys.contains(attack) || keys.contains(use))) {
            before.remove(attack); before.remove(use);
            warmup=2;
        }
        initial=Set.copyOf(before);
    }

    Set<Integer> keys() { return warmup>0?initial:keys; }

    boolean endTick() {
        if(warmup>0) { warmup--; return false; }
        return --remaining<=0;
    }
}
