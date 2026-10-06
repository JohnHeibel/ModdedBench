// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package baritone.gtnh.pathing;

import baritone.compat.BlockPos;
import java.util.*;

/**
 * A bounded copy of the blocks around some clicks: collision shapes (where a body fits and stands), selection shapes
 * (what a look ray stops at), and whether each cell holds a tile entity or fluid. Copied from the game on its thread,
 * then used off it: a hypothetical world is this copy with some cells replaced. Cells outside the bounds are unknown
 * (solid, never standable, never removable).
 */
public final class ClickSpace {
    /** One cell. kind is a TerrainGrid kind; replaceable: a block goes into it (air, tall grass); id is for reports. */
    public record Voxel(byte kind,List<CollisionBox> collision,List<CollisionBox> selection,boolean tile,boolean replaceable,String id) {
        public static Voxel air(){return new Voxel(TerrainGrid.CLEAR,List.of(),List.of(),false,true,"minecraft:air");}
        public static Voxel full(BlockPos p,String id,boolean tile){CollisionBox b=new CollisionBox(p.getX(),p.getY(),p.getZ(),p.getX()+1,p.getY()+1,p.getZ()+1);return new Voxel(TerrainGrid.SUPPORT,List.of(b),List.of(b),tile,false,id);}
        public boolean fluid(){return kind==TerrainGrid.WATER||kind==TerrainGrid.HAZARD&&collision.isEmpty();}
    }
    public final BlockPos min,max;
    private final Map<BlockPos,Voxel> cells,changed;
    /** Where tile entities are: those of the copy, and those the game named around it. */
    private final Collection<BlockPos> tiles;
    /** The copy these changes were made to, which keeps the one whole grid; null for the copy itself. */
    private final ClickSpace base;
    private TerrainGrid grid;
    public ClickSpace(BlockPos min,BlockPos max,Map<BlockPos,Voxel> cells){this(min,max,cells,List.of());}
    /**
     * around: tile entities beyond the copy's edge, so that a cell near the edge knows of a machine just past it.
     * cells is the space's from here on, never written again, and not copied, least of all with Map.copyOf: that and
     * Set.copyOf are open-addressed tables, and the positions of a box hash (Vec3i) to neighbouring values with repeats,
     * which costs such a table the square of their number to fill and as much again to read (85,184 blocks: 4.9 s to
     * make, 15 microseconds a lookup). So no set or map of positions that may be large is one of those.
     */
    public ClickSpace(BlockPos min,BlockPos max,Map<BlockPos,Voxel> cells,Collection<BlockPos> around) {
        this(min,max,cells,Map.of(),null,new HashSet<>(around));
        cells.forEach((p,v)->{if(v.tile())tiles.add(p);});
    }
    private ClickSpace(BlockPos min,BlockPos max,Map<BlockPos,Voxel> cells,Map<BlockPos,Voxel> changed,ClickSpace base,Collection<BlockPos> tiles){this.min=min;this.max=max;this.cells=cells;this.changed=changed;this.base=base;this.tiles=tiles;}
    private static boolean within(BlockPos a,BlockPos b,int d){return Math.abs(a.getX()-b.getX())<=d&&Math.abs(a.getY()-b.getY())<=d&&Math.abs(a.getZ()-b.getZ())<=d;}
    /** A tile entity within d (Chebyshev) of p, a changed cell that holds one included. */
    public boolean nearTile(BlockPos p,int d) {
        for(BlockPos t:tiles)if(within(t,p,d))return true;
        for(var e:changed.entrySet())if(e.getValue().tile()&&within(e.getKey(),p,d))return true;
        return false;
    }
    public boolean inside(BlockPos p){return p.getX()>=min.getX()&&p.getY()>=min.getY()&&p.getZ()>=min.getZ()&&p.getX()<=max.getX()&&p.getY()<=max.getY()&&p.getZ()<=max.getZ();}
    private static final Voxel UNKNOWN=new Voxel(TerrainGrid.UNKNOWN,List.of(),List.of(),false,false,"unknown");
    public Voxel at(BlockPos p){if(!inside(p))return UNKNOWN;Voxel v=changed.get(p);return v!=null?v:cells.getOrDefault(p,UNKNOWN);}
    public Voxel at(int x,int y,int z){return at(new BlockPos(x,y,z));}
    public boolean known(BlockPos p){return at(p).kind()!=TerrainGrid.UNKNOWN;}
    /** The same space with some cells replaced: a placed block, a removed one. The copy is shared, so this costs the changes only. */
    public ClickSpace with(Map<BlockPos,Voxel> changes) {
        if(changes.isEmpty())return this;
        Map<BlockPos,Voxel> next=new HashMap<>(changed);changes.forEach((p,v)->{if(inside(p))next.put(p,v);});
        return new ClickSpace(min,max,cells,next,base==null?this:base,tiles);
    }
    public ClickSpace with(BlockPos p,Voxel v){return with(Map.of(p,v));}
    /**
     * Where a body whose feet are in this cell stands, as the navigation graph computes it (NaN: nowhere). That depends
     * on the cells within one block sideways and three up and down, so only a stance that near a changed cell is
     * computed afresh; every other one is the copy's.
     */
    public double standingY(BlockPos feet) {
        for(BlockPos c:changed.keySet())if(Math.abs(c.getX()-feet.getX())<=1&&Math.abs(c.getZ()-feet.getZ())<=1&&Math.abs(c.getY()-feet.getY())<=3)
            return grid(new BlockPos(feet.getX()-1,feet.getY()-3,feet.getZ()-1),new BlockPos(feet.getX()+1,feet.getY()+3,feet.getZ()+1)).standingY(feet);
        return (base==null?this:base).grid().standingY(feet);
    }
    /** Footing and body clearance exactly as the navigation graph computes them, for the copy without its changes. */
    private synchronized TerrainGrid grid(){if(grid==null)grid=grid(min,max);return grid;}
    private TerrainGrid grid(BlockPos lo,BlockPos hi) {
        int w=hi.getX()-lo.getX()+1,h=hi.getY()-lo.getY()+1,d=hi.getZ()-lo.getZ()+1;
        byte[] kinds=new byte[w*h*d];Map<BlockPos,List<CollisionBox>> shapes=new HashMap<>();
        for(int x=0;x<w;x++)for(int z=0;z<d;z++)for(int y=0;y<h;y++) {
            BlockPos p=new BlockPos(lo.getX()+x,lo.getY()+y,lo.getZ()+z);Voxel v=at(p);
            kinds[(x*d+z)*h+y]=v.kind();
            if(v.kind()==TerrainGrid.PARTIAL||v.kind()==TerrainGrid.BLOCKED||v.kind()==TerrainGrid.LADDER)shapes.put(p,v.collision());
        }
        return new TerrainGrid(lo.getX(),lo.getY(),lo.getZ(),w,h,d,kinds,new float[kinds.length*3],shapes,Map.of());
    }
}
