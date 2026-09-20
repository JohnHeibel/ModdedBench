// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh.pathing;

import java.util.List;

/** A saved route leg constrains newly calculated paths, never blindly replays old controls. */
public final class CorridorWorld implements WorldView {
    private final WorldView source;
    private final BlockPos start,end;
    private final double radius;
    public CorridorWorld(WorldView source,BlockPos start,BlockPos end,double radius) {
        if(!Double.isFinite(radius)||radius<1||radius>16) throw new IllegalArgumentException("corridor radius must be 1..16");
        this.source=source;this.start=start;this.end=end;this.radius=radius;
    }
    public boolean contains(BlockPos pos) {
        double dx=(double)end.x()-start.x(),dy=(double)end.y()-start.y(),dz=(double)end.z()-start.z();
        double px=(double)pos.x()-start.x(),py=(double)pos.y()-start.y(),pz=(double)pos.z()-start.z();
        double length=dx*dx+dy*dy+dz*dz;
        double t=length==0?0:Math.max(0,Math.min(1,(px*dx+py*dy+pz*dz)/length));
        double x=px-t*dx,y=py-t*dy,z=pz-t*dz;
        return x*x+y*y+z*z<=radius*radius;
    }
    @Override public boolean isLoaded(int x,int y,int z) {return source.isLoaded(x,y,z);}
    @Override public List<Move> moves(BlockPos from) {return source.moves(from).stream().filter(m->contains(m.destination())).toList();}
}
