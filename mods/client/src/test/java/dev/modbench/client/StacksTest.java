// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.client;

import net.minecraft.nbt.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class StacksTest {
    private static NBTTagCompound fields(boolean reverse) {
        NBTTagCompound tag=new NBTTagCompound();
        // These keys collide in HashMap, reproducing different native iteration orders.
        if(reverse) {tag.setInteger("BB",2);tag.setInteger("Aa",1);}
        else {tag.setInteger("Aa",1);tag.setInteger("BB",2);}
        return tag;
    }
    @Test public void pickupCopyOrderDoesNotChangeNestedIdentity() {
        NBTTagCompound first=new NBTTagCompound(),second=new NBTTagCompound();
        NBTTagList a=new NBTTagList(),b=new NBTTagList();
        a.appendTag(fields(false));b.appendTag(fields(true));
        first.setTag("tool",a);second.setTag("tool",b);
        String before=first.toString();
        assertNotEquals(before,second.toString());
        assertEquals(first,second);
        assertEquals(Stacks.canonical(first),Stacks.canonical(second));
        assertEquals(Stacks.fingerprint(Stacks.canonical(first)),Stacks.fingerprint(Stacks.canonical(second)));
        assertEquals(before,first.toString());
        assertEquals(1,a.tagCount());
    }
    @Test public void valuesTypesAndListOrderRemainDistinct() {
        assertNotEquals(Stacks.canonical(new NBTTagByte((byte)1)),Stacks.canonical(new NBTTagInt(1)));
        assertNotEquals(Stacks.canonical(new NBTTagInt(1)),Stacks.canonical(new NBTTagInt(2)));
        NBTTagList a=new NBTTagList(),b=new NBTTagList();
        a.appendTag(new NBTTagInt(1));a.appendTag(new NBTTagInt(2));
        b.appendTag(new NBTTagInt(2));b.appendTag(new NBTTagInt(1));
        assertNotEquals(Stacks.canonical(a),Stacks.canonical(b));
    }
    @Test public void canonicalObservationsRemainNativeRecipeInputs() throws Exception {
        NBTTagCompound tag=fields(false);NBTTagList numbers=new NBTTagList();
        numbers.appendTag(new NBTTagInt(1));numbers.appendTag(new NBTTagInt(2));
        tag.setTag("numbers",numbers);tag.setTag("array",new NBTTagIntArray(new int[]{3,4}));
        tag.setString("label","Flint Pickaxe");
        assertEquals(tag,JsonToNBT.func_150315_a(Stacks.canonical(tag)));
    }
}
