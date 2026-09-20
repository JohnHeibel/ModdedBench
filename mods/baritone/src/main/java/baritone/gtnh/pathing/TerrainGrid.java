// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh.pathing;

import baritone.compat.BlockPos;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

/** Immutable collision snapshot for footing, headroom, ladder and fluid observation. Unknown cells are impassable, never air. */
public final class TerrainGrid {
    public static final byte UNKNOWN = 0, CLEAR = 1, SUPPORT = 2, BLOCKED = 3, HAZARD = 4, WATER = 5, PARTIAL = 6, LADDER = 7;
    private static final int[][] DIRECTIONS = {{1,0},{-1,0},{0,1},{0,-1}};
    private final int minX, minY, minZ, width, height, depth;
    private final byte[] cells;
    private final float[] flows;
    private final Map<BlockPos,List<CollisionBox>> shapes;
    private final Map<BlockPos,LadderFacing> ladders;
    private final java.util.concurrent.ConcurrentHashMap<BlockPos,Double> floorCache=new java.util.concurrent.ConcurrentHashMap<>();
    public TerrainGrid(int minX, int minY, int minZ, int width, int height, int depth, byte[] cells) {
        this(minX,minY,minZ,width,height,depth,cells,new float[cells.length*3]);
    }
    public TerrainGrid(int minX, int minY, int minZ, int width, int height, int depth, byte[] cells, float[] flows) {
        this(minX,minY,minZ,width,height,depth,cells,flows,Map.of(),Map.of());
    }
    public TerrainGrid(int minX,int minY,int minZ,int width,int height,int depth,byte[] cells,float[] flows,
                       Map<BlockPos,List<CollisionBox>> shapes,Map<BlockPos,LadderFacing> ladders) {
        if (cells.length != width * height * depth) throw new IllegalArgumentException("grid dimensions");
        if (flows.length != cells.length * 3) throw new IllegalArgumentException("flow dimensions");
        this.minX = minX; this.minY = minY; this.minZ = minZ;
        this.width = width; this.height = height; this.depth = depth; this.cells = cells.clone();
        this.flows = flows.clone();
        Map<BlockPos,List<CollisionBox>> copied=new HashMap<>();
        shapes.forEach((p,boxes)->copied.put(p,List.copyOf(boxes)));
        this.shapes=Map.copyOf(copied);this.ladders=Map.copyOf(ladders);
    }
    public byte cell(int x, int y, int z) {
        x -= minX; y -= minY; z -= minZ;
        if (x < 0 || y < 0 || z < 0 || x >= width || y >= height || z >= depth) return UNKNOWN;
        return cells[(x * depth + z) * height + y];
    }
    public boolean clear(int x, int y, int z) { return cell(x,y,z) == CLEAR; }
    public boolean standable(int x, int y, int z) {
        return Double.isFinite(standingY(new BlockPos(x,y,z)));
    }
    public List<CollisionBox> boxes(int x,int y,int z) {
        List<CollisionBox> shape=shapes.get(new BlockPos(x,y,z));
        if(shape!=null) return shape;
        byte c=cell(x,y,z);
        return c==SUPPORT||c==BLOCKED ? List.of(new CollisionBox(x,y,z,x+1,y+1,z+1)):List.of();
    }
    /** The graph keeps block identities; exact feet heights come from the collision surface. */
    public double standingY(BlockPos p) {
        return floorCache.computeIfAbsent(p,key->surface(key.getX()+.5,key.getZ()+.5,key.getY()-.0001,key.getY()+.9999));
    }
    private double surface(double x,double z,double low,double high) {
        double best=Double.NaN;
        for(int bx=(int)Math.floor(x-.3);bx<=Math.floor(x+.3);bx++)
            for(int bz=(int)Math.floor(z-.3);bz<=Math.floor(z+.3);bz++)
                for(int by=(int)Math.floor(low)-1;by<=Math.floor(high);by++) {
                    byte c=cell(bx,by,bz);
                    if(c!=SUPPORT && c!=PARTIAL) continue;
                    for(CollisionBox b:boxes(bx,by,bz)) if(b.maxY()>=low && b.maxY()<=high && b.supports(x,z)
                            && (!Double.isFinite(best)||b.maxY()>best) && bodyClear(x,b.maxY(),z,1.8,false)) best=b.maxY();
                }
        return best;
    }
    public boolean bodyClear(double x,double y,double z,double height,boolean allowWater) {
        CollisionBox body=new CollisionBox(x-.3,y+.001,z-.3,x+.3,y+height-.001,z+.3);
        for(int bx=(int)Math.floor(body.minX())-1;bx<=Math.floor(body.maxX())+1;bx++)
            for(int bz=(int)Math.floor(body.minZ())-1;bz<=Math.floor(body.maxZ())+1;bz++)
                for(int by=(int)Math.floor(body.minY())-1;by<=Math.floor(body.maxY());by++) {
                    byte c=cell(bx,by,bz);
                    if((c==UNKNOWN||c==HAZARD||(!allowWater&&c==WATER)) && body.intersects(new CollisionBox(bx,by,bz,bx+1,by+1,bz+1))) return false;
                    for(CollisionBox b:boxes(bx,by,bz)) if(body.intersects(b)) return false;
                }
        return true;
    }
    public LadderFacing ladder(BlockPos p) {return ladders.getOrDefault(p,LadderFacing.NONE);}
    public boolean climbable(BlockPos p) {return ladder(p)!=LadderFacing.NONE && bodyClear(p.getX()+.5,p.getY(),p.getZ()+.5,1.8,false);}
    public boolean water(int x,int y,int z) { return cell(x,y,z)==WATER; }
    public boolean passable(int x,int y,int z) { return clear(x,y,z)||water(x,y,z)||cell(x,y,z)==LADDER; }
    public double flow(int x,int y,int z,int axis) {
        if (!water(x,y,z)) return 0;
        return flows[(((x-minX)*depth+(z-minZ))*height+(y-minY))*3+axis];
    }
    public boolean swimmable(int x,int y,int z) {
        if (!water(x,y,z)||!passable(x,y+1,z)||!passable(x,y+2,z)) return false;
        // Do not plan through a waterfall that can pull the player into a deep shaft.
        if (flow(x,y,z,1)<-.5 && cell(x,y-1,z)!=SUPPORT) return false;
        for (int[] d:DIRECTIONS) for(int h=-1;h<=1;h++) {
            byte neighbor=cell(x+d[0],y+h,z+d[1]);
            if(neighbor==HAZARD || neighbor==UNKNOWN) return false;
        }
        return true;
    }
    public boolean traversable(int x,int y,int z) { return standable(x,y,z)||swimmable(x,y,z)||climbable(new BlockPos(x,y,z)); }
}
