// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.compat;

public final class Vec3d {
    public static final Vec3d ZERO=new Vec3d(0,0,0);
    public final double x,y,z;
    public Vec3d(double x,double y,double z){this.x=x;this.y=y;this.z=z;}
    public Vec3d(Vec3i p){this(p.getX(),p.getY(),p.getZ());}
    public Vec3d add(double x,double y,double z){return new Vec3d(this.x+x,this.y+y,this.z+z);}
    public Vec3d add(Vec3d p){return add(p.x,p.y,p.z);}
    public Vec3d subtract(Vec3d p){return add(-p.x,-p.y,-p.z);}
    public Vec3d subtract(double x,double y,double z){return add(-x,-y,-z);}
    public Vec3d scale(double n){return new Vec3d(x*n,y*n,z*n);}
    public double lengthVector(){return Math.sqrt(x*x+y*y+z*z);}
    public double lengthSquared(){return x*x+y*y+z*z;}
    public Vec3d normalize(){double n=lengthVector();return n<1e-4?ZERO:scale(1/n);}
    public double dotProduct(Vec3d p){return x*p.x+y*p.y+z*p.z;}
    public double squareDistanceTo(Vec3d p){return subtract(p).lengthSquared();}
    public double distanceTo(Vec3d p){return Math.sqrt(squareDistanceTo(p));}
    public net.minecraft.util.Vec3 nativeVector(){return net.minecraft.util.Vec3.createVectorHelper(x,y,z);}
    public static Vec3d fromNative(net.minecraft.util.Vec3 p){return new Vec3d(p.xCoord,p.yCoord,p.zCoord);}
    @Override public boolean equals(Object o){return o instanceof Vec3d p&&x==p.x&&y==p.y&&z==p.z;}
    @Override public int hashCode(){return java.util.Objects.hash(x,y,z);}
}
