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
    private final Map<BlockPos,Voxel> cells;
    private TerrainGrid grid;
    public ClickSpace(BlockPos min,BlockPos max,Map<BlockPos,Voxel> cells){this.min=min;this.max=max;this.cells=Map.copyOf(cells);}
    public boolean inside(BlockPos p){return p.getX()>=min.getX()&&p.getY()>=min.getY()&&p.getZ()>=min.getZ()&&p.getX()<=max.getX()&&p.getY()<=max.getY()&&p.getZ()<=max.getZ();}
    private static final Voxel UNKNOWN=new Voxel(TerrainGrid.UNKNOWN,List.of(),List.of(),false,false,"unknown");
    public Voxel at(BlockPos p){if(!inside(p))return UNKNOWN;return cells.getOrDefault(p,UNKNOWN);}
    public Voxel at(int x,int y,int z){return at(new BlockPos(x,y,z));}
    public boolean known(BlockPos p){return at(p).kind()!=TerrainGrid.UNKNOWN;}
    /** The same space with some cells replaced: a placed block, a removed one. */
    public ClickSpace with(Map<BlockPos,Voxel> changes) {
        if(changes.isEmpty())return this;
        Map<BlockPos,Voxel> next=new HashMap<>(cells);changes.forEach((p,v)->{if(inside(p))next.put(p,v);});
        return new ClickSpace(min,max,next);
    }
    public ClickSpace with(BlockPos p,Voxel v){return with(Map.of(p,v));}
    /** Footing and body clearance exactly as the navigation graph computes them. */
    public TerrainGrid grid() {
        if(grid!=null)return grid;
        int w=max.getX()-min.getX()+1,h=max.getY()-min.getY()+1,d=max.getZ()-min.getZ()+1;
        byte[] kinds=new byte[w*h*d];Map<BlockPos,List<CollisionBox>> shapes=new HashMap<>();
        for(int x=0;x<w;x++)for(int z=0;z<d;z++)for(int y=0;y<h;y++) {
            BlockPos p=new BlockPos(min.getX()+x,min.getY()+y,min.getZ()+z);Voxel v=at(p);
            kinds[(x*d+z)*h+y]=v.kind();
            if(v.kind()==TerrainGrid.PARTIAL||v.kind()==TerrainGrid.BLOCKED||v.kind()==TerrainGrid.LADDER)shapes.put(p,v.collision());
        }
        return grid=new TerrainGrid(min.getX(),min.getY(),min.getZ(),w,h,d,kinds,new float[kinds.length*3],shapes,Map.of());
    }
}
