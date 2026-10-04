// SPDX-License-Identifier: LGPL-3.0-or-later
package baritone.gtnh.pathing;

/** Which of its own plan cells a construction job may break. */
public final class PlanBreaks {
    private PlanBreaks() {}
    /**
     * Whether the block now in a plan cell may be broken, by the builder or by a path through it.
     * clear: the plan wants the cell empty. correct: the world already matches the plan there. deferred: an explicit-air
     * cell that may hold an access support until the cleanup phase. replace: the job may remove what is in the way of a
     * cell. pending: this job clicked a block into the cell and has not yet seen it match.
     *
     * A finished solid cell is never broken, whatever replace says. Upstream charges ten times the dig for one and then
     * walks through; the builder sees the hole within reach, fills it before the walk passes, the walk is planned again
     * through the same cell, and so on (live 2026-10-03, the roof corner 280,81,-262: eight placements, no progress).
     * Where finished work is the only way through, the search fails and the job's stall watchdog ends it.
     */
    public static boolean allowed(boolean clear,boolean correct,boolean deferred,boolean replace,boolean pending) {
        if(deferred)return false;
        if(!clear&&(correct||!replace))return false;
        return !pending||correct;
    }
}
