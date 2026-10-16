// SPDX-License-Identifier: MIT
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.client;

import java.awt.Point;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Mouse;

/** Logical GUI coordinates across legacy LWJGL and GTNH's GLFW compatibility layer. */
final class GuiPointer {
    static Point move(GuiScreen gui,int x,int y) throws Exception {
        Minecraft mc=Minecraft.getMinecraft();
        // A screen opened by an RPC can precede Minecraft's own cursor release, and absolute movement is
        // ignored while the gameplay cursor is grabbed. Release it the game's way when the game holds focus,
        // so closing the screen grabs it again.
        if(Mouse.isGrabbed()) {if(mc.inGameHasFocus) mc.setIngameNotInFocus();else Mouse.setGrabbed(false);}
        int px=(int)Math.ceil((x+0.5)*mc.displayWidth/gui.width);
        int py=(int)Math.ceil((y+0.5)*mc.displayHeight/gui.height);
        Class<?> display=null;
        try { display=Class.forName("org.lwjglx.opengl.Display"); }
        catch(ClassNotFoundException legacy) { /* Original LWJGL uses bottom-left cursor coordinates. */ }
        Point actual=new Point(-1,-1);
        if(display!=null) {
            // lwjgl3ify 2.1.16 sets two moves aside to ignore whenever the cursor is released (a screen opened from play) and
            // spends one on its own re-centring; the other waits for GLFW's echo of it, which comes only to a focused window.
            // The client usually runs without focus, so one is left over per screen opened, and the backlog swallowed the
            // moves below (every miss in the 2026-10-15 run read the window centre). Nothing else moves this cursor.
            var ignored=Class.forName("org.lwjglx.input.Mouse").getDeclaredField("ignoreNextMove");ignored.setAccessible(true);ignored.setInt(null,0);
        }
        for(int attempt=0;attempt<3;attempt++) {
            if(display!=null) {
                // lwjgl3ify 2.1.16's legacy setter uses GLFW's top-left origin and applies DPI twice.
                // Use the native GLFW entry point and its regular compatibility-layer move event.
                long window=((Number)display.getMethod("getWindow").invoke(null)).longValue();
                double scale=((Number)display.getMethod("getPixelScaleFactor").invoke(null)).doubleValue();
                Class.forName("org.lwjgl.glfw.GLFW").getMethod("glfwSetCursorPos",long.class,double.class,double.class)
                    .invoke(null,window,px/scale,py/scale);
                Class.forName("org.lwjglx.input.Mouse").getMethod("addMoveEvent",double.class,double.class)
                    .invoke(null,px/scale,py/scale);
                // Pump GLFW so the read-back below sees this position, not the previous one.
                Class.forName("org.lwjgl.glfw.GLFW").getMethod("glfwPollEvents").invoke(null);
            } else Mouse.setCursorPosition(px,mc.displayHeight-py);
            Mouse.poll();
            actual=new Point(Mouse.getX()*gui.width/mc.displayWidth,gui.height-Mouse.getY()*gui.height/mc.displayHeight-1);
            if(actual.x==x && actual.y==y) return actual;
            // Grab/ungrab transitions can deliberately suppress the first native move events.
        }
        throw new IllegalArgumentException("native GUI pointer did not reach "+x+","+y+"; actual "+actual.x+","+actual.y);
    }
}
