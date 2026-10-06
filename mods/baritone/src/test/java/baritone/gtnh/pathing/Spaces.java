// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package baritone.gtnh.pathing;

import baritone.compat.BlockPos;
import baritone.gtnh.pathing.ClickSpace.Voxel;
import java.util.*;

/** Synthetic click worlds for tests: a box of air (or stone) with cells set by hand. */
final class Spaces {
    private final BlockPos min,max;
    private final Map<BlockPos,Voxel> cells=new HashMap<>();
    private final List<BlockPos> around=new ArrayList<>();
    Spaces(int x0,int y0,int z0,int x1,int y1,int z1,boolean solid) {
        min=new BlockPos(x0,y0,z0);max=new BlockPos(x1,y1,z1);
        for(int x=x0;x<=x1;x++)for(int y=y0;y<=y1;y++)for(int z=z0;z<=z1;z++){BlockPos p=new BlockPos(x,y,z);cells.put(p,solid?Voxel.full(p,"minecraft:stone",false):Voxel.air());}
    }
    static BlockPos p(int x,int y,int z){return new BlockPos(x,y,z);}
    Spaces solid(int x,int y,int z){BlockPos p=p(x,y,z);cells.put(p,Voxel.full(p,"minecraft:stone",false));return this;}
    Spaces tile(int x,int y,int z,String id){BlockPos p=p(x,y,z);cells.put(p,Voxel.full(p,id,true));return this;}
    Spaces air(int x,int y,int z){cells.put(p(x,y,z),Voxel.air());return this;}
    Spaces water(int x,int y,int z){cells.put(p(x,y,z),new Voxel(TerrainGrid.WATER,List.of(),List.of(),false,true,"minecraft:water"));return this;}
    /** A bottom slab: collision and selection the lower half. */
    Spaces slab(int x,int y,int z){CollisionBox b=new CollisionBox(x,y,z,x+1,y+.5,z+1);cells.put(p(x,y,z),new Voxel(TerrainGrid.PARTIAL,List.of(b),List.of(b),false,false,"minecraft:stone_slab"));return this;}
    Spaces floor(int y){for(int x=min.getX();x<=max.getX();x++)for(int z=min.getZ();z<=max.getZ();z++)solid(x,y,z);return this;}
    /** A tile entity the game names beyond the copy's edge. */
    Spaces tileOutside(int x,int y,int z){around.add(p(x,y,z));return this;}
    /** A block with no collision that is not replaceable: a torch, a lever. */
    Spaces attached(int x,int y,int z){CollisionBox b=new CollisionBox(x+.4,y,z+.4,x+.6,y+.6,z+.6);cells.put(p(x,y,z),new Voxel(TerrainGrid.CLEAR,List.of(),List.of(b),false,false,"minecraft:torch"));return this;}
    ClickSpace build(){return new ClickSpace(min,max,new HashMap<>(cells),around);}
    static final Vantages.Body BODY=new Vantages.Body(1.62,4.5);
}
