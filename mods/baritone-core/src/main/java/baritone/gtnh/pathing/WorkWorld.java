// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh.pathing;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Optional, costed excavation/bridging over an immutable terrain snapshot. */
public final class WorkWorld implements WorldView {
    public record Work(List<BlockPos> breakBlocks,BlockPos placeBlock,double cost) {
        public boolean needed() {return !breakBlocks.isEmpty() || placeBlock!=null;}
    }
    private final TerrainGrid terrain;
    private final Map<BlockPos,Double> breakTicks;
    private final int placementBudget;
    private final java.util.function.Predicate<BlockPos> editAllowed;
    /** Immutable schematic costs, captured on the game thread before search. */
    public interface Construction {
        double placementCost(BlockPos p);
        double breakMultiplier(BlockPos p);
        default boolean replaceFluid(BlockPos p){return false;}
    }
    private final Construction construction;
    public WorkWorld(TerrainGrid terrain,Map<BlockPos,Double> breakTicks,int placementBudget) {
        this(terrain,breakTicks,placementBudget,p->true);
    }
    public WorkWorld(TerrainGrid terrain,Map<BlockPos,Double> breakTicks,int placementBudget,java.util.function.Predicate<BlockPos> editAllowed) {
        this(terrain,breakTicks,placementBudget,editAllowed,null);
    }
    public WorkWorld(TerrainGrid terrain,Map<BlockPos,Double> breakTicks,int placementBudget,java.util.function.Predicate<BlockPos> editAllowed,Construction construction) {
        this.terrain=terrain;this.breakTicks=Map.copyOf(breakTicks);this.placementBudget=placementBudget;this.editAllowed=editAllowed;this.construction=construction;
    }
    @Override public boolean isLoaded(int x,int y,int z) {return terrain.isLoaded(x,y,z);}
    private boolean safeNeighbors(BlockPos p) {
        for(int[] d:new int[][]{{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}}) {
            byte c=terrain.cell(p.x()+d[0],p.y()+d[1],p.z()+d[2]);
            if(c==TerrainGrid.UNKNOWN || c==TerrainGrid.HAZARD || c==TerrainGrid.WATER) return false;
        }
        return true;
    }
    public Work workAt(BlockPos feet) {
        return workAt(feet,1,true);
    }
    private Work workAt(BlockPos feet,int top,boolean permitPlacement) {
        List<BlockPos> breaks=new ArrayList<>();double cost=0;
        for(int dy=top;dy>=0;dy--) {
            BlockPos p=new BlockPos(feet.x(),feet.y()+dy,feet.z());
            if(terrain.clear(p.x(),p.y(),p.z())) continue;
            if(!editAllowed.test(p)) return null;
            Double ticks=breakTicks.get(p);
            if(ticks==null || !Double.isFinite(ticks) || ticks<=0 || !safeNeighbors(p)) return null;
            double multiplier=construction==null?1:construction.breakMultiplier(p);if(!Double.isFinite(multiplier))return null;
            breaks.add(p);cost+=(ticks+12)*multiplier;
        }
        BlockPos floor=new BlockPos(feet.x(),feet.y()-1,feet.z());
        BlockPos place=null;
        if(terrain.cell(floor.x(),floor.y(),floor.z())!=TerrainGrid.SUPPORT) {
            boolean replaceable=terrain.clear(floor.x(),floor.y(),floor.z())||construction!=null&&construction.replaceFluid(floor);
            if(!permitPlacement || !editAllowed.test(floor) || placementBudget<=0 || !replaceable || !safeNeighbors(floor)) return null;
            double price=construction==null?30:construction.placementCost(floor);if(!Double.isFinite(price))return null;
            place=floor;cost+=price;
        }
        return new Work(List.copyOf(breaks),place,cost);
    }
    public Work workForEdge(BlockPos from,BlockPos to) {
        int dy=to.y()-from.y();
        int horizontal=Math.abs(to.x()-from.x())+Math.abs(to.z()-from.z());
        if(construction!=null&&dy==1&&(horizontal==0||horizontal==1)) {
            Work target=workAt(to,1,true);if(target==null)return null;
            // Ascending sweeps the source head through its third block too.
            BlockPos head=new BlockPos(from.x(),from.y()+2,from.z());
            if(horizontal==1&&!terrain.clear(head.x(),head.y(),head.z())) {
                Double ticks=breakTicks.get(head);double multiplier=construction.breakMultiplier(head);
                if(!editAllowed.test(head)||ticks==null||!Double.isFinite(ticks)||ticks<=0||!Double.isFinite(multiplier)||!safeNeighbors(head))return null;
                List<BlockPos> breaks=new ArrayList<>(target.breakBlocks());if(!breaks.contains(head))breaks.add(0,head);
                target=new Work(List.copyOf(breaks),target.placeBlock(),target.cost()+(ticks+12)*multiplier);
            }
            return target;
        }
        if(horizontal!=1 || dy>0 || dy< -1)return null;
        // MovementDescend clears dest.up(2), dest.up(), dest, in that order.
        // The source support is never part of this column, and a descent must
        // land on existing full support rather than install a floor mid-fall.
        return workAt(to,dy==-1?2:1,dy==0);
    }
    public double feetY(BlockPos p) {
        double y=terrain.feetY(p);
        return Double.isFinite(y)?y:workAt(p)!=null?p.y():Double.NaN;
    }
    @Override public List<Move> moves(BlockPos from) {
        List<Move> out=new ArrayList<>(terrain.moves(from));
        // Flat work requires dry full-block footing (possibly installed by the prior edge).
        Work here=workAt(from);
        if(here==null) return out;
        for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
          for(int dy:construction==null?new int[]{0,-1}:new int[]{0,-1,1}) {
            BlockPos to=new BlockPos(from.x()+d[0],from.y()+dy,from.z()+d[1]);
            Work work=workForEdge(from,to);
            if(work==null || out.stream().anyMatch(m->m.destination().equals(to))) continue;
            String kind=dy==0?"WALK":dy==1?"ASCEND":"DESCEND";
            out.add(new Move(to,ActionCosts.WALK_ONE_BLOCK_COST+(dy==-1?4:dy==1?18:0)+work.cost(),work.needed()?"WORK_"+kind:kind));
          }
        }
        if(construction!=null) {
            BlockPos up=new BlockPos(from.x(),from.y()+1,from.z());Work pillar=workForEdge(from,up);
            if(pillar!=null&&pillar.placeBlock()!=null)out.add(new Move(up,ActionCosts.WALK_ONE_BLOCK_COST+25+pillar.cost(),"WORK_PILLAR"));
        }
        return out;
    }
    public int placementsRequired(List<BlockPos> path) {
        return (int)path.stream().distinct().map(this::workAt).filter(w->w!=null && w.placeBlock()!=null).count();
    }
}
