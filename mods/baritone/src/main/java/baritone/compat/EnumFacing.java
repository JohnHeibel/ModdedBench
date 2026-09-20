// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.compat;

public enum EnumFacing {
    DOWN(0,-1,0,Axis.Y),UP(0,1,0,Axis.Y),NORTH(0,0,-1,Axis.Z),SOUTH(0,0,1,Axis.Z),WEST(-1,0,0,Axis.X),EAST(1,0,0,Axis.X);
    private final Vec3i vector; private final Axis axis;
    EnumFacing(int x,int y,int z,Axis axis){vector=new Vec3i(x,y,z);this.axis=axis;}
    public int getXOffset(){return vector.getX();} public int getYOffset(){return vector.getY();} public int getZOffset(){return vector.getZ();}
    public Vec3i getDirectionVec(){return vector;} public Axis getAxis(){return axis;}
    public AxisDirection getAxisDirection(){return getXOffset()+getYOffset()+getZOffset()>0?AxisDirection.POSITIVE:AxisDirection.NEGATIVE;}
    public EnumFacing getOpposite(){return values()[ordinal()^1];}
    public int getIndex(){return ordinal();}
    public static EnumFacing byHorizontalIndex(int index){return switch(Math.floorMod(index,4)){case 0->SOUTH;case 1->WEST;case 2->NORTH;default->EAST;};}
    public static EnumFacing getFront(int side){if(side<0||side>=6)throw new IllegalArgumentException("invalid side");return values()[side];}
    public enum Axis { X,Y,Z }
    public enum Plane implements Iterable<EnumFacing> {
        HORIZONTAL,VERTICAL;
        public java.util.Iterator<EnumFacing> iterator(){return (this==HORIZONTAL?java.util.List.of(NORTH,EAST,SOUTH,WEST):java.util.List.of(UP,DOWN)).iterator();}
    }
    public enum AxisDirection {POSITIVE(1),NEGATIVE(-1);private final int offset;AxisDirection(int offset){this.offset=offset;}public int getOffset(){return offset;}}
}
