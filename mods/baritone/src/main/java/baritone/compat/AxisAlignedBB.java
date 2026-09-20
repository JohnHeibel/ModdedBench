// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.compat;

public final class AxisAlignedBB {
    public AxisAlignedBB(BlockPos min,BlockPos max){this(min.getX(),min.getY(),min.getZ(),max.getX(),max.getY(),max.getZ());}
    public final double minX,minY,minZ,maxX,maxY,maxZ;
    public AxisAlignedBB(double ax,double ay,double az,double bx,double by,double bz){minX=ax;minY=ay;minZ=az;maxX=bx;maxY=by;maxZ=bz;}
    public AxisAlignedBB(BlockPos p){this(p.getX(),p.getY(),p.getZ(),p.getX()+1,p.getY()+1,p.getZ()+1);}
    public AxisAlignedBB offset(BlockPos p){return new AxisAlignedBB(minX+p.getX(),minY+p.getY(),minZ+p.getZ(),maxX+p.getX(),maxY+p.getY(),maxZ+p.getZ());}
    public AxisAlignedBB offset(double x,double y,double z){return new AxisAlignedBB(minX+x,minY+y,minZ+z,maxX+x,maxY+y,maxZ+z);}
    public AxisAlignedBB grow(double x,double y,double z){return new AxisAlignedBB(minX-x,minY-y,minZ-z,maxX+x,maxY+y,maxZ+z);}
    public net.minecraft.util.AxisAlignedBB nativeBox(){return net.minecraft.util.AxisAlignedBB.getBoundingBox(minX,minY,minZ,maxX,maxY,maxZ);}
    public static AxisAlignedBB fromNative(net.minecraft.util.AxisAlignedBB b){return b==null?null:new AxisAlignedBB(b.minX,b.minY,b.minZ,b.maxX,b.maxY,b.maxZ);}
}
