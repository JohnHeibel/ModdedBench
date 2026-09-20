// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh.pathing;

/** Immutable world-coordinate collision geometry copied from Forge on the game thread. */
public record CollisionBox(double minX,double minY,double minZ,double maxX,double maxY,double maxZ) {
    public CollisionBox {
        if(!Double.isFinite(minX+minY+minZ+maxX+maxY+maxZ) || minX>maxX || minY>maxY || minZ>maxZ)
            throw new IllegalArgumentException("invalid collision box");
    }
    public boolean intersects(CollisionBox b) {
        return maxX>b.minX && minX<b.maxX && maxY>b.minY && minY<b.maxY && maxZ>b.minZ && minZ<b.maxZ;
    }
    public boolean supports(double x,double z) {
        return maxX>x-.29 && minX<x+.29 && maxZ>z-.29 && minZ<z+.29;
    }
}
