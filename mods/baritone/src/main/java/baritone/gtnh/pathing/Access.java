// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package baritone.gtnh.pathing;

import baritone.compat.BlockPos;
import java.util.*;
import java.util.function.Predicate;
import static baritone.gtnh.pathing.WorkSpec.*;

/**
 * Temporary access: with no vantage for a click, remove the fewest cells that open one, click, and put them back.
 * Opt-in per job. Which cells may go is decided here from what the copy of the world shows, never from block names:
 * a cell holding a tile entity never goes, nor one beside a fluid, nor one the plan places, nor an unknown one; a cell
 * within tileDistance of any tile entity goes only inside one of the caller's bounds. Protection is the caller's
 * predicate (the world memory's protected regions), applied on top. Near a tile entity the restore must be the identical
 * block, within NEAR_TILE_RESTORE_TICKS; elsewhere a substitute from inventory is allowed and reported.
 */
public record Access(boolean allowed,List<Bounds> bounds,int tileDistance,int maxCells) {
    public static final Access OFF=new Access(false,List.of(),DEFAULT_TILE_DISTANCE(),3);
    static int DEFAULT_TILE_DISTANCE(){return 4;}
    public static Access parse(Map<String,Object> m) {
        fields(m,Set.of("allow","bounds","tileDistance","maxCells"));
        List<Bounds> bounds=new ArrayList<>();
        if(m.containsKey("bounds")){List<?> b=list(m.get("bounds"));if(b.size()>8)throw new IllegalArgumentException("at most 8 access bounds");for(Object row:b)bounds.add(WorkSpec.bounds(object(row)));}
        return new Access(bool(m,"allow",false),List.copyOf(bounds),integer(m,"tileDistance",DEFAULT_TILE_DISTANCE(),0,16),integer(m,"maxCells",3,1,8));
    }
    public boolean inBounds(BlockPos p){return bounds.stream().anyMatch(b->b.contains(p));}
    /** Why this cell may not be removed, or null. planned: cells the plan places; protectedCell: the world memory's verdict. */
    public String refusal(ClickSpace s,BlockPos p,Set<BlockPos> planned,Predicate<BlockPos> protectedCell) {
        var v=s.at(p);
        if(v.kind()==TerrainGrid.UNKNOWN)return "unknown";
        if(v.replaceable())return "empty";
        if(v.tile())return "tile_entity";
        if(v.fluid())return "fluid";
        if(planned.contains(p))return "plan_cell";
        if(protectedCell.test(p))return "protected";
        for(int f=0;f<6;f++)if(s.at(ClickSpec.offset(p,f)).fluid())return "beside_fluid";
        if(!inBounds(p)&&tileWithin(s,p,tileDistance)!=null)return "near_tile_entity";
        return null;
    }
    /**
     * Ticks a removed cell may stay open when it lies within tileDistance of a tile entity (allowed there by bounds).
     * Some machines recheck their structure a fixed time after the first neighbour change; putting the identical block
     * back inside that window means the recheck sees no change.
     */
    public static final int NEAR_TILE_RESTORE_TICKS=40;
    /** May a cell be put back as a different block than it was? Only away from tile entities: next to one, a substitute can leave a structure permanently incomplete. */
    public boolean substituteAllowed(ClickSpace s,BlockPos p){return tileWithin(s,p,tileDistance)==null;}
    /** The nearest tile entity within distance d (Chebyshev) of p in the copy, or null. Cells outside the copy count as unknown, not as tile entities. */
    public static BlockPos tileWithin(ClickSpace s,BlockPos p,int d) {
        BlockPos best=null;double bestD=Double.MAX_VALUE;
        for(int x=-d;x<=d;x++)for(int y=-d;y<=d;y++)for(int z=-d;z<=d;z++) {
            BlockPos q=new BlockPos(p.getX()+x,p.getY()+y,p.getZ()+z);
            if(s.at(q).tile()){double dd=x*x+y*y+z*z;if(dd<bestD){bestD=dd;best=q;}}
        }
        return best;
    }
    public Map<String,Object> json() {
        Map<String,Object> out=new LinkedHashMap<>();out.put("allow",allowed);out.put("tileDistance",tileDistance);out.put("maxCells",maxCells);
        out.put("bounds",bounds.stream().map(b->Map.of("min",point(b.min()),"max",point(b.max()))).toList());return out;
    }
}
