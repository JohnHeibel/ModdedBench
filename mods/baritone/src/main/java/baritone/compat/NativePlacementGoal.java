// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.compat;

import baritone.api.pathing.goals.Goal;
import java.util.Set;

/** Refines a source builder goal with client-captured native placement legality. */
public record NativePlacementGoal(Goal source,Set<BlockPos> legal) implements Goal {
    public NativePlacementGoal {legal=Set.copyOf(legal);}
    @Override public boolean isInGoal(int x,int y,int z){return source.isInGoal(x,y,z)&&legal.contains(new BlockPos(x,y,z));}
    @Override public double heuristic(int x,int y,int z){return source.heuristic(x,y,z);}
    @Override public double heuristic(){return source.heuristic();}
}
