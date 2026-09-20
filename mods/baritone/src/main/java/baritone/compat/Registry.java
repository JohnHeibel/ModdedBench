// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.compat;

import net.minecraft.block.Block;
import net.minecraft.item.Item;

/** The port's one spelling of 1.7.10 registry names. */
public final class Registry {
    private Registry() {}
    public static String name(Block block){return Block.blockRegistry.getNameForObject(block);}
    public static String name(Item item){return Item.itemRegistry.getNameForObject(item);}
    /** Exact lookup: the 1.7.10 block registry answers unknown names with its default, air. */
    public static Block block(String id){
        Object value=Block.blockRegistry.getObject(id);
        if(!(value instanceof Block block)||!id.equals(name(block)))throw new IllegalArgumentException("unknown block "+id);
        return block;
    }
}
