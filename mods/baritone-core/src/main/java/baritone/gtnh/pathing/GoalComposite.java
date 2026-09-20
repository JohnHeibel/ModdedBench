/*
 * This file is part of Baritone.
 * Licensed under the GNU Lesser General Public License, version 3 or later.
 * See src/api/java/baritone/api/pathing/goals/GoalComposite.java and LICENSE.
 */
package baritone.gtnh.pathing;

import java.util.List;

/** Upstream GoalComposite: any member satisfies the goal; heuristic is the minimum. */
public final class GoalComposite implements Goal {
    private final List<Goal> goals;
    public GoalComposite(List<? extends Goal> goals) {
        if(goals.isEmpty())throw new IllegalArgumentException("goals must not be empty");
        this.goals=List.copyOf(goals);
    }
    @Override public boolean isInGoal(int x,int y,int z) {
        for(Goal goal:goals)if(goal.isInGoal(x,y,z))return true;
        return false;
    }
    @Override public double heuristic(int x,int y,int z) {
        double min=Double.MAX_VALUE;
        for(Goal goal:goals)min=Math.min(min,goal.heuristic(x,y,z));
        return min;
    }
}
