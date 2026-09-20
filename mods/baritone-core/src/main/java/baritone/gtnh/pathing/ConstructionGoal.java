// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
/* Derived from BuilderProcess GoalPlace / JankyGoalComposite. LGPL-3.0-or-later. */
package baritone.gtnh.pathing;

import java.util.*;

/** Work goals with placement/lower-layer heuristic priority and clearing fallbacks. */
public final class ConstructionGoal implements Goal {
    private final Map<BlockPos,Integer> placements;
    private final Set<BlockPos> breaks;
    public ConstructionGoal(Map<BlockPos,Integer> placements,Set<BlockPos> breaks) {
        if(placements.isEmpty()&&breaks.isEmpty())throw new IllegalArgumentException("no construction goals");
        this.placements=Map.copyOf(placements);this.breaks=Set.copyOf(breaks);
    }
    @Override public boolean isInGoal(int x,int y,int z){BlockPos p=new BlockPos(x,y,z);return placements.containsKey(p)||breaks.contains(p);}
    @Override public double heuristic(int x,int y,int z) {
        double best=Double.MAX_VALUE;
        if(!placements.isEmpty())for(var e:placements.entrySet()){var p=e.getKey();best=Math.min(best,e.getValue()*100.0+new GoalBlock(p.x(),p.y(),p.z()).heuristic(x,y,z));}
        else for(var p:breaks)best=Math.min(best,new GoalBlock(p.x(),p.y(),p.z()).heuristic(x,y,z));
        return best;
    }
}
