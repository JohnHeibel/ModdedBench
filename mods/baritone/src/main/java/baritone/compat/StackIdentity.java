// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.compat;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import java.util.Objects;

/** Client-captured identity; comparisons on the search worker invoke no item or registry callbacks. */
public final class StackIdentity {
    private final Item item;
    private final int metadata;
    private final NBTTagCompound tag;
    private StackIdentity(ItemStack stack) {
        item=stack.getItem();metadata=stack.getItemDamage();
        tag=stack.hasTagCompound()?(NBTTagCompound)stack.getTagCompound().copy():new NBTTagCompound();
    }
    public static StackIdentity capture(ItemStack stack){return stack==null?null:new StackIdentity(stack);}
    @Override public boolean equals(Object other){return other instanceof StackIdentity key&&item==key.item&&metadata==key.metadata&&tag.equals(key.tag);}
    @Override public int hashCode(){return Objects.hash(item,metadata,tag);}
}
