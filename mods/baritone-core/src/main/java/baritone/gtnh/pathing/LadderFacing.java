// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh.pathing;

/** Direction to press into the attached climbing face. NONE is intentionally unsupported. */
public enum LadderFacing {
    NONE(0,0), NORTH(0,-1), SOUTH(0,1), WEST(-1,0), EAST(1,0);
    public final int dx,dz;
    LadderFacing(int dx,int dz) {this.dx=dx;this.dz=dz;}
}
