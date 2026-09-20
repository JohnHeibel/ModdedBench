// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.compat;
public final class ChunkPos {
    public final int x,z;
    public ChunkPos(int x,int z){this.x=x;this.z=z;}
    public ChunkPos(BlockPos p){this(p.getX()>>4,p.getZ()>>4);}
    public static long asLong(int x,int z){return (long)x&0xffffffffL|(long)z<<32;}
    @Override public boolean equals(Object other){return other instanceof ChunkPos p&&x==p.x&&z==p.z;}
    @Override public int hashCode(){return java.util.Objects.hash(x,z);}
}
