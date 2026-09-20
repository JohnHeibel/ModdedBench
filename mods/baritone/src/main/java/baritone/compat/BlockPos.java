// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.compat;

public class BlockPos extends Vec3i {
    public static final BlockPos ORIGIN = new BlockPos(0,0,0);
    public BlockPos(int x,int y,int z) { super(x,y,z); }
    public BlockPos(double x,double y,double z) { super(x,y,z); }
    public BlockPos(Vec3i p) { this(p.getX(),p.getY(),p.getZ()); }
    public BlockPos(Vec3d p) { this(p.x,p.y,p.z); }
    public BlockPos add(int x,int y,int z) { return x==0&&y==0&&z==0?this:new BlockPos(getX()+x,getY()+y,getZ()+z); }
    public BlockPos add(Vec3i p) { return add(p.getX(),p.getY(),p.getZ()); }
    public BlockPos subtract(Vec3i p) { return add(-p.getX(),-p.getY(),-p.getZ()); }
    public BlockPos offset(EnumFacing d) { return offset(d,1); }
    public BlockPos offset(EnumFacing d,int n) { return add(d.getXOffset()*n,d.getYOffset()*n,d.getZOffset()*n); }
    public BlockPos up() { return up(1); } public BlockPos up(int n) { return add(0,n,0); }
    public BlockPos down() { return down(1); } public BlockPos down(int n) { return add(0,-n,0); }
    public BlockPos north() { return north(1); } public BlockPos north(int n) { return add(0,0,-n); }
    public BlockPos south() { return south(1); } public BlockPos south(int n) { return add(0,0,n); }
    public BlockPos east() { return east(1); } public BlockPos east(int n) { return add(n,0,0); }
    public BlockPos west() { return west(1); } public BlockPos west(int n) { return add(-n,0,0); }
    public BlockPos toImmutable() { return this; }
    public long toLong() { return ((long)getX()&0x3ffffffL)<<38|((long)getY()&0xfffL)<<26|((long)getZ()&0x3ffffffL); }
    public static BlockPos fromLong(long v) { return new BlockPos((int)(v>>38),(int)(v<<26>>52),(int)(v<<38>>38)); }
    public static final class MutableBlockPos extends BlockPos {
        private int mx,my,mz;
        public MutableBlockPos() { super(0,0,0); }
        public MutableBlockPos setPos(int x,int y,int z) { mx=x;my=y;mz=z;return this; }
        @Override public int getX(){return mx;} @Override public int getY(){return my;} @Override public int getZ(){return mz;}
        @Override public BlockPos toImmutable(){return new BlockPos(mx,my,mz);}
    }
}
