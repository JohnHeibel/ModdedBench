// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.api;

import java.util.Map;

/** Placement metadata of net.minecraft.item.ItemStack values, including mod-specific item blocks. */
public interface PlacementInfo {
    int initialMetadata(Object stack);
    Map<String,Object> describe(Object stack);
}
