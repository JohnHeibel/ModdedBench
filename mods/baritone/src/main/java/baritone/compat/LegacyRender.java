// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.compat;

import java.awt.Color;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.util.ResourceLocation;
import static org.lwjgl.opengl.GL11.*;

/** Native 1.7 tessellation boundary; upstream owns path/goal/selection geometry. */
public final class LegacyRender {
    private LegacyRender() {}
    public static final class Buffer {
        private double x,y,z;
        public void begin(int mode){Tessellator.instance.startDrawing(mode);}
        public Buffer pos(double x,double y,double z){this.x=x;this.y=y;this.z=z;return this;}
        public Buffer color(float r,float g,float b,float a){Tessellator.instance.setColorRGBA_F(r,g,b,a);return this;}
        public void endVertex(){Tessellator.instance.addVertex(x,y,z);}
    }
    /** A native textured goal beam. All GL state is restored for GTNH renderers. */
    public static void beacon(double x,double y,double z,double time,Color color,boolean ignoreDepth){
        glPushAttrib(GL_ALL_ATTRIB_BITS);
        try {
            Minecraft.getMinecraft().getTextureManager().bindTexture(new ResourceLocation("textures/entity/beacon_beam.png"));
            glEnable(GL_TEXTURE_2D);glEnable(GL_BLEND);glBlendFunc(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA);
            glDisable(GL_LIGHTING);glDisable(GL_CULL_FACE);glDepthMask(false);
            if(ignoreDepth)glDisable(GL_DEPTH_TEST);
            Tessellator t=Tessellator.instance;double phase=time*.025,scroll=-time*.2;
            t.startDrawingQuads();t.setColorRGBA_F(color.getRed()/255f,color.getGreen()/255f,color.getBlue()/255f,.55f);
            for(int i=0;i<4;i++){
                double a=phase+i*Math.PI/2,b=phase+(i+1)*Math.PI/2;
                double ax=x+.5+Math.cos(a)*.3,az=z+.5+Math.sin(a)*.3;
                double bx=x+.5+Math.cos(b)*.3,bz=z+.5+Math.sin(b)*.3;
                t.addVertexWithUV(ax,y+256,az,0,scroll+256);t.addVertexWithUV(ax,y,az,0,scroll);
                t.addVertexWithUV(bx,y,bz,1,scroll);t.addVertexWithUV(bx,y+256,bz,1,scroll+256);
            }
            t.draw();
        } finally {glPopAttrib();}
    }
}
