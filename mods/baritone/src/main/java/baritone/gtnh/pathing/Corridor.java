// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh.pathing;

import baritone.compat.BlockPos;
/** A saved route leg constrains newly calculated paths, never blindly replays old controls. */
public final class Corridor {
    private final BlockPos start,end;
    private final double radius;
    public Corridor(BlockPos start,BlockPos end,double radius) {
        if(!Double.isFinite(radius)||radius<1||radius>16) throw new IllegalArgumentException("corridor radius must be 1..16");
        this.start=start;this.end=end;this.radius=radius;
    }
    /** Capsule around the segment, so elevation and both end caps count. */
    public boolean contains(BlockPos pos) {
        double dx=(double)end.getX()-start.getX(),dy=(double)end.getY()-start.getY(),dz=(double)end.getZ()-start.getZ();
        double px=(double)pos.getX()-start.getX(),py=(double)pos.getY()-start.getY(),pz=(double)pos.getZ()-start.getZ();
        double length=dx*dx+dy*dy+dz*dz;
        double t=length==0?0:Math.max(0,Math.min(1,(px*dx+py*dy+pz*dz)/length));
        double x=px-t*dx,y=py-t*dy,z=pz-t*dz;
        return x*x+y*y+z*z<=radius*radius;
    }
}
