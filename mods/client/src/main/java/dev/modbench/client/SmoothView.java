// SPDX-License-Identifier: MIT
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.client;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;
import java.lang.reflect.Field;

/**
 * Presentation only: a pose set by code (Baritone, jobs, act.look) is blended across the following tick, as vanilla
 * blends a mouse turn. Code sets rotationYaw before the player's update, which copies it into prevRotationYaw, so the
 * renderer had nothing to interpolate and the camera stepped at 20 Hz. After each tick the previous pose becomes the
 * one the last frame showed. The aim itself, its packets and tick-time rays (getMouseOver(1)) see the true pose.
 */
final class SmoothView {
    private final Minecraft mc=Minecraft.getMinecraft();
    private final Field timer=timerField();
    private EntityPlayer player;
    private float yaw,pitch;
    private boolean shown;

    /** After a frame: remember the pose it showed (mouse turns move both ends, so a still blend shows the true pose). */
    void rendered() {
        EntityPlayer p=mc.thePlayer;
        if(p!=player) {player=p;shown=false;}
        if(p==null||timer==null) return;
        float t=partialTicks();
        yaw=p.prevRotationYaw+(p.rotationYaw-p.prevRotationYaw)*t;
        pitch=p.prevRotationPitch+(p.rotationPitch-p.prevRotationPitch)*t;
        shown=true;
    }
    /** After a simulation tick: blend from the last shown pose (the nearer way round) to the tick's pose. */
    void ticked() {
        EntityPlayer p=mc.thePlayer;
        if(p==null||p!=player||!shown) return;
        p.prevRotationYaw=p.rotationYaw-MathHelper.wrapAngleTo180_float(p.rotationYaw-yaw);
        p.prevRotationPitch=pitch;
    }
    /** While gated no tick finishes the blend: show (and let screenshots capture) the true pose. */
    void settle() {
        EntityPlayer p=mc.thePlayer;
        if(p==null) return;
        p.prevRotationYaw=p.rotationYaw;p.prevRotationPitch=p.rotationPitch;
    }
    private float partialTicks() {
        try {return ((net.minecraft.util.Timer)timer.get(mc)).renderPartialTicks;}
        catch(ReflectiveOperationException|RuntimeException e) {return 1;}
    }
    /** Minecraft's private Timer, found by type so dev and obfuscated names both work. */
    private static Field timerField() {
        for(Field f:Minecraft.class.getDeclaredFields()) if(f.getType()==net.minecraft.util.Timer.class) {
            try {f.setAccessible(true);return f;} catch(RuntimeException e) {return null;}
        }
        return null;
    }
}
