// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package baritone.gtnh.pathing;

import baritone.compat.BlockPos;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Temporary access: with no vantage for a click, a job that may break blocks removes the fewest cells that open one,
 * clicks, and puts the same blocks back. Which cells may go is decided here from what the copy of the world shows,
 * never from block names: not a cell holding a tile entity or a fluid, nor one beside a fluid, nor a plan cell, nor an
 * unknown one, nor one within TILE_DISTANCE of any tile entity (a machine may check the blocks around it), nor one
 * the caller keeps (a protected cell, or a block that could not be put back as it was), nor one with anything beside
 * it that is not a whole block (what hangs on a block or stands on it, a torch, a rail, a door, comes off with it).
 */
public final class Access {
    private Access() {}
    public static final int TILE_DISTANCE=4;
    /** The most cells one click may have removed for it. */
    public static final int MAX_CELLS=3;
    /** Why this cell may not be removed, or null. planned: the plan's cells; kept: the caller's verdict. */
    public static String refusal(ClickSpace s,BlockPos p,Set<BlockPos> planned,Predicate<BlockPos> kept) {
        var v=s.at(p);
        if(v.kind()==TerrainGrid.UNKNOWN)return "unknown";
        if(v.replaceable())return "empty";
        if(v.tile())return "tile_entity";
        if(v.fluid())return "fluid";
        if(planned.contains(p))return "plan_cell";
        if(kept.test(p))return "kept";
        for(int f=0;f<6;f++) {
            var n=s.at(ClickSpec.offset(p,f));
            if(n.fluid())return "beside_fluid";
            if(!n.replaceable()&&n.kind()!=TerrainGrid.SUPPORT)return "beside_part_block";
        }
        if(nearTile(s,p))return "near_tile_entity";
        return null;
    }
    /** A tile entity within TILE_DISTANCE (Chebyshev) of p: in the copy, or beyond its edge where the game named one. */
    public static boolean nearTile(ClickSpace s,BlockPos p){return s.nearTile(p,TILE_DISTANCE);}
}
