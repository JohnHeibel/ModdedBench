// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.client;

import dev.modbench.bridge.Json;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import java.nio.ByteBuffer;

/** Presentation only: retain the last rendered image on the GPU, independent of simulation gates. */
public final class PausedFrame {
    private final Minecraft mc=Minecraft.getMinecraft();
    private final ClientClock clock;
    private Object world;
    private GuiScreen screen;
    private int texture=-1,width,height;
    private boolean valid,visible,refresh;
    private String error;
    private long captures;

    PausedFrame(ClientClock clock) {this.clock=clock;}
    void refresh() {refresh=true;}
    Object status() {return Json.object("visible",visible,"cached",valid,"width",width,"height",height,"captures",captures,"label",label(),"error",error);}
    private String label() {return "Paused: "+clock.pauseReason().replace('_',' ');}

    void rendered() {
        visible=false;
        if(mc.theWorld==null) {release();world=null;screen=null;return;}
        if(world!=mc.theWorld) {release();world=mc.theWorld;}
        if(mc.displayWidth<1||mc.displayHeight<1) return;
        // A native screen opened deliberately while paused must remain inspectable.
        // Capture its rendered result once; ordinary frozen frames never recapture.
        boolean screenChanged=screen!=mc.currentScreen;screen=mc.currentScreen;
        int activeTexture=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try {
            if(!valid||width!=mc.displayWidth||height!=mc.displayHeight||!clock.isPaused()||refresh||screenChanged) {
                capture();refresh=false;
            }
            if(clock.isPaused()) {draw();visible=true;}
            error=null;
        } catch(RuntimeException failure) {
            error=failure.toString(); // Rendering failure must not change simulation state or lose controls.
            valid=false;
        } finally {
            GL11.glPopAttrib();GL13.glActiveTexture(activeTexture);
        }
    }
    private void release() {
        if(texture>=0) GL11.glDeleteTextures(texture);
        texture=-1;valid=false;visible=false;
    }
    private void capture() {
        if(texture<0) texture=GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);
        if(!valid||width!=mc.displayWidth||height!=mc.displayHeight) {
            width=mc.displayWidth;height=mc.displayHeight;
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,width,height,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,(ByteBuffer)null);
        }
        GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D,0,0,0,0,0,width,height);
        valid=true;captures++;
    }
    private void draw() {
        ScaledResolution resolution=new ScaledResolution(mc,width,height);
        double w=resolution.getScaledWidth_double(),h=resolution.getScaledHeight_double();
        int mode=GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        GL11.glMatrixMode(GL11.GL_PROJECTION);GL11.glPushMatrix();GL11.glLoadIdentity();GL11.glOrtho(0,w,h,0,-1,1);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);GL11.glPushMatrix();GL11.glLoadIdentity();
        try {
            GL11.glViewport(0,0,width,height);
            GL11.glDisable(GL11.GL_DEPTH_TEST);GL11.glDisable(GL11.GL_LIGHTING);GL11.glDisable(GL11.GL_FOG);
            GL11.glDisable(GL11.GL_ALPHA_TEST);GL11.glDisable(GL11.GL_BLEND);GL11.glDisable(GL11.GL_SCISSOR_TEST);GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glEnable(GL11.GL_TEXTURE_2D);GL11.glColor4f(1,1,1,1);GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glTexCoord2f(0,1);GL11.glVertex2d(0,0);
            GL11.glTexCoord2f(0,0);GL11.glVertex2d(0,h);
            GL11.glTexCoord2f(1,0);GL11.glVertex2d(w,h);
            GL11.glTexCoord2f(1,1);GL11.glVertex2d(w,0);
            GL11.glEnd();
            Gui.drawRect(0,0,resolution.getScaledWidth(),25,0xDC101820);
            String text=mc.fontRenderer.trimStringToWidth(label(),Math.max(1,resolution.getScaledWidth()-20));
            mc.fontRenderer.drawStringWithShadow(text,(resolution.getScaledWidth()-mc.fontRenderer.getStringWidth(text))/2,8,0xFFE6A8);
        } finally {
            GL11.glMatrixMode(GL11.GL_MODELVIEW);GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_PROJECTION);GL11.glPopMatrix();GL11.glMatrixMode(mode);
        }
    }
}
