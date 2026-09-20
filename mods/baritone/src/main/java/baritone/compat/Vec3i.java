// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.compat;

/** Integer vector API boundary for the pre-BlockPos Minecraft version. */
public class Vec3i implements Comparable<Vec3i> {
    public static final Vec3i NULL_VECTOR = new Vec3i(0, 0, 0);
    protected final int x, y, z;
    public Vec3i(int x, int y, int z) { this.x=x; this.y=y; this.z=z; }
    public Vec3i(double x, double y, double z) { this((int)Math.floor(x),(int)Math.floor(y),(int)Math.floor(z)); }
    public int getX() { return x; }
    public int getY() { return y; }
    public int getZ() { return z; }
    public double distanceSq(double x, double y, double z) { double dx=getX()-x,dy=getY()-y,dz=getZ()-z; return dx*dx+dy*dy+dz*dz; }
    public double distanceSq(Vec3i other) { return distanceSq(other.getX(),other.getY(),other.getZ()); }
    public double getDistance(int x,int y,int z) { return Math.sqrt(distanceSq(x,y,z)); }
    public Vec3i crossProduct(Vec3i other){return new Vec3i(getY()*other.getZ()-getZ()*other.getY(),getZ()*other.getX()-getX()*other.getZ(),getX()*other.getY()-getY()*other.getX());}
    @Override public int compareTo(Vec3i o) { int c=Integer.compare(getY(),o.getY()); if(c==0)c=Integer.compare(getZ(),o.getZ());return c==0?Integer.compare(getX(),o.getX()):c; }
    @Override public boolean equals(Object o) { return o instanceof Vec3i p&&getX()==p.getX()&&getY()==p.getY()&&getZ()==p.getZ(); }
    @Override public int hashCode() { return (getY()+getZ()*31)*31+getX(); }
    @Override public String toString() { return "["+getX()+","+getY()+","+getZ()+"]"; }
}
