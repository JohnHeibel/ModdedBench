// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
package dev.modbench.control;

import java.lang.reflect.Method;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.util.*;
import net.minecraft.world.World;

/** Native mouse-over context shared by direct interactions and Baritone. */
public final class NativeTargeting {
    private static boolean resolved;
    private static Method clientProxy,computing;
    private static Method heldBoundsRule;
    private static java.lang.reflect.Field heldBounds,computingFlag;
    private static int depth;
    private NativeTargeting() {}
    private static void resolve() {
        if(resolved)return;
        if(cpw.mods.fml.common.Loader.isModLoaded("gregtech"))try {
            clientProxy=Class.forName("gregtech.GTMod").getMethod("clientProxy");
            computing=clientProxy.getReturnType().getMethod("setComputingPickBlock",boolean.class);
            heldBoundsRule=clientProxy.getReturnType().getDeclaredMethod("shouldHeldItemForceFullBlockBB");heldBoundsRule.setAccessible(true);
            heldBounds=clientProxy.getReturnType().getDeclaredField("heldItemForcesFullBlockBB");heldBounds.setAccessible(true);
            computingFlag=clientProxy.getReturnType().getDeclaredField("isComputingPickBlock");computingFlag.setAccessible(true);
        }catch(ReflectiveOperationException error){throw new IllegalStateException("GregTech native targeting context unavailable",error);}
        resolved=true;
    }
    public static <T> T withContext(Supplier<T> action) {
        resolve();if(computing==null||depth>0)return action.get();
        Object proxy;boolean previousBounds,previousComputing;
        try {
            proxy=clientProxy.invoke(null);if(proxy==null)return action.get();
            previousBounds=heldBounds.getBoolean(proxy);previousComputing=computingFlag.getBoolean(proxy);
            // GT caches this during its own tick. Native prediction may query a
            // newly selected item or a projected crouch before that tick runs.
            // Re-evaluate GT's own rule; do not duplicate its item/tool lists.
            boolean projectedBounds=(Boolean)heldBoundsRule.invoke(null);
            computing.invoke(proxy,true);heldBounds.setBoolean(proxy,projectedBounds);
        }
        catch(ReflectiveOperationException error){throw new IllegalStateException("cannot enter native targeting context",error);}
        depth++;
        try{return action.get();}
        finally {
            depth--;
            try{heldBounds.setBoolean(proxy,previousBounds);computing.invoke(proxy,previousComputing);}catch(ReflectiveOperationException error){throw new IllegalStateException("cannot release native targeting context",error);}
        }
    }
    public static MovingObjectPosition refresh() {
        return withContext(()->{Minecraft mc=Minecraft.getMinecraft();mc.entityRenderer.getMouseOver(1);return mc.objectMouseOver;});
    }
    public static MovingObjectPosition trace(World world,Vec3 start,Vec3 end,boolean liquid,boolean ignoreWithoutBox,boolean returnMiss) {
        return withContext(()->world.func_147447_a(start,end,liquid,ignoreWithoutBox,returnMiss));
    }
}
