// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh.pathing;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

/** Immutable collision/terrain snapshot. Unknown cells are impassable, never air. */
public final class TerrainGrid implements WorldView {
    public static final byte UNKNOWN = 0, CLEAR = 1, SUPPORT = 2, BLOCKED = 3, HAZARD = 4, WATER = 5, PARTIAL = 6, LADDER = 7;
    private static final int[][] DIRECTIONS = {{1,0},{-1,0},{0,1},{0,-1}};
    private final int minX, minY, minZ, width, height, depth;
    private final byte[] cells;
    private final float[] flows;
    private final Map<BlockPos,List<CollisionBox>> shapes;
    private final Map<BlockPos,LadderFacing> ladders;
    private final double stepHeight;
    private final java.util.concurrent.ConcurrentHashMap<BlockPos,Double> floorCache=new java.util.concurrent.ConcurrentHashMap<>();
    public TerrainGrid(int minX, int minY, int minZ, int width, int height, int depth, byte[] cells) {
        this(minX,minY,minZ,width,height,depth,cells,new float[cells.length*3]);
    }
    public TerrainGrid(int minX, int minY, int minZ, int width, int height, int depth, byte[] cells, float[] flows) {
        this(minX,minY,minZ,width,height,depth,cells,flows,Map.of(),Map.of(),.5);
    }
    public TerrainGrid(int minX,int minY,int minZ,int width,int height,int depth,byte[] cells,float[] flows,
                       Map<BlockPos,List<CollisionBox>> shapes,Map<BlockPos,LadderFacing> ladders,double stepHeight) {
        if (cells.length != width * height * depth) throw new IllegalArgumentException("grid dimensions");
        if (flows.length != cells.length * 3) throw new IllegalArgumentException("flow dimensions");
        this.minX = minX; this.minY = minY; this.minZ = minZ;
        this.width = width; this.height = height; this.depth = depth; this.cells = cells.clone();
        this.flows = flows.clone();
        Map<BlockPos,List<CollisionBox>> copied=new HashMap<>();
        shapes.forEach((p,boxes)->copied.put(p,List.copyOf(boxes)));
        this.shapes=Map.copyOf(copied);this.ladders=Map.copyOf(ladders);
        this.stepHeight=Math.max(0,Math.min(.6,stepHeight));
    }
    public byte cell(int x, int y, int z) {
        x -= minX; y -= minY; z -= minZ;
        if (x < 0 || y < 0 || z < 0 || x >= width || y >= height || z >= depth) return UNKNOWN;
        return cells[(x * depth + z) * height + y];
    }
    @Override public boolean isLoaded(int x, int y, int z) { return cell(x,y,z) != UNKNOWN; }
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
        return floorCache.computeIfAbsent(p,key->surface(key.x()+.5,key.z()+.5,key.y()-.0001,key.y()+.9999));
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
    public boolean climbable(BlockPos p) {return ladder(p)!=LadderFacing.NONE && bodyClear(p.x()+.5,p.y(),p.z()+.5,1.8,false);}
    public double feetY(BlockPos p) {return water(p.x(),p.y(),p.z())||climbable(p)?p.y():standingY(p);}
    /** Sweep the player's footprint through the risers, including the stair's intermediate half-step. */
    private boolean canStep(BlockPos from,BlockPos to,double start,double end) {
        double y=start;
        for(int i=1;i<=8;i++) {
            double t=i/8.0,x=from.x()+.5+(to.x()-from.x())*t,z=from.z()+.5+(to.z()-from.z())*t;
            double next=surface(x,z,y-.5001,y+stepHeight+.0001);
            if(!Double.isFinite(next)) return false;
            // Raising the player must not pass the head through a low ceiling.
            if(!bodyClear(x,Math.max(y,next),z,1.8,false)) return false;
            y=next;
        }
        return Math.abs(y-end)<.001;
    }
    public Move groundMove(BlockPos from,BlockPos to) {
        double fy=standingY(from),ty=standingY(to),rise=ty-fy;
        if(!Double.isFinite(fy+ty)||rise>1.001||rise< -3.001) return null;
        if(canStep(from,to,fy,ty)) {
            String kind=Math.abs(rise)<.001?"WALK":rise>0?"STEP_UP":"STEP_DOWN";
            return new Move(to,ActionCosts.WALK_ONE_BLOCK_COST+GoalBlock.calculate(0,from.y()-to.y(),0),kind);
        }
        if(rise>.001) {
            // MovementAscend requires source jump clearance and the two body
            // cells at the destination, not empty space above its ceiling.
            // Native upward collision can cap a jump while still reaching the
            // landing. Requiring the unobstructed 1.25-block apex everywhere
            // incorrectly rejects an ordinary two-high raised doorway by .05.
            if(!bodyClear(from.x()+.5,fy,from.z()+.5,rise+1.8,false)) return null;
            for(int i=1;i<=8;i++) if(!bodyClear(from.x()+.5+(to.x()-from.x())*i/8.0,ty,from.z()+.5+(to.z()-from.z())*i/8.0,1.8,false)) return null;
            return new Move(to,ActionCosts.WALK_ONE_BLOCK_COST+ActionCosts.JUMP_ONE_BLOCK_COST,"ASCEND");
        }
        if(rise<-.001) {
            if(!bodyClear(to.x()+.5,ty,to.z()+.5,fy-ty+1.8,false)) return null;
            for(int i=1;i<=8;i++) if(!bodyClear(from.x()+.5+(to.x()-from.x())*i/8.0,fy,from.z()+.5+(to.z()-from.z())*i/8.0,1.8,false)) return null;
            return new Move(to,ActionCosts.WALK_OFF_BLOCK_COST+ActionCosts.FALL_N_BLOCKS_COST[(int)Math.ceil(-rise)]+ActionCosts.CENTER_AFTER_FALL_COST,"DESCEND");
        }
        return null;
    }
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
    @Override public List<Move> moves(BlockPos from) {
        List<Move> out = new ArrayList<>(4);
        int x = from.x(), y = from.y(), z = from.z();
        boolean climbing=climbable(from);
        boolean swimming=swimmable(x,y,z);
        if (!standable(x,y,z) && !swimming && !climbing) return out;
        if(climbing) for(int dy:new int[]{1,-1}) {
            BlockPos p=new BlockPos(x,y+dy,z);
            if(climbable(p) && ladder(p)==ladder(from)) out.add(new Move(p,dy>0?ActionCosts.LADDER_UP_ONE_COST:ActionCosts.LADDER_DOWN_ONE_COST,dy>0?"CLIMB_UP":"CLIMB_DOWN"));
        }
        if (swimming && swimmable(x,y+1,z)) {
            out.add(new Move(new BlockPos(x,y+1,z),20,"SWIM_UP"));
        }
        // Submerged starts recover upwards; normal routes keep a breathing surface.
        if (swimming && water(x,y+1,z)) return out;
        for (int[] dir : DIRECTIONS) {
            int nx=x+dir[0], nz=z+dir[1];
            for(int dy:new int[]{0,-1}) {
                BlockPos p=new BlockPos(nx,y+dy,nz);
                if(!swimming && climbable(p) && bodyClear(nx+.5,y,nz+.5,1.8,false))
                    out.add(new Move(p,ActionCosts.WALK_ONE_BLOCK_COST+ActionCosts.LADDER_DOWN_ONE_COST,"CLIMB_ENTER"));
            }
            if(climbing) {
                for(int dy:new int[]{0,1}) {
                    BlockPos p=new BlockPos(nx,y+dy,nz);
                    double py=standingY(p);
                    if(Double.isFinite(py) && py>=y && py<=y+1.001 && bodyClear(x+.5,y,z+.5,py-y+1.8,false)
                            && bodyClear(nx+.5,py,nz+.5,1.8,false))
                        out.add(new Move(p,ActionCosts.LADDER_UP_ONE_COST+ActionCosts.WALK_ONE_BLOCK_COST,"CLIMB_EXIT"));
                }
                continue;
            }
            if (swimmable(nx,y,nz) && clear(nx,y+1,nz)) {
                double cost=FluidPolicy.swimCost(flow(nx,y,nz,0),flow(nx,y,nz,2),dir[0],dir[1]);
                out.add(new Move(new BlockPos(nx,y,nz),cost,"SWIM"));
            } else if (swimming && standable(nx,y+1,nz) && clear(x,y+2,z) && clear(nx,y+3,nz)) {
                out.add(new Move(new BlockPos(nx,y+1,nz),ActionCosts.WALK_ONE_IN_WATER_COST+ActionCosts.JUMP_ONE_BLOCK_COST,"SWIM_EXIT"));
            } else if (swimming && standable(nx,y,nz)) {
                out.add(new Move(new BlockPos(nx,y,nz),ActionCosts.WALK_ONE_IN_WATER_COST,"WADE_EXIT"));
            } else if(!swimming) {
                for(int dy=1;dy>=-3;dy--) {
                    Move move=groundMove(from,new BlockPos(nx,y+dy,nz));
                    if(move!=null) out.add(move);
                }
            }
            if (clear(nx,y,nz) && clear(nx,y+1,nz)) {
                for (int fall=1; fall<=3; fall++) {
                    if (!clear(nx,y-fall+1,nz)) break;
                    if (swimmable(nx,y-fall,nz) && clear(nx,y-fall+1,nz)) {
                        out.add(new Move(new BlockPos(nx,y-fall,nz),ActionCosts.WALK_ONE_IN_WATER_COST+ActionCosts.FALL_N_BLOCKS_COST[fall],"SWIM_ENTER"));
                        break;
                    }
                }
            }
        }
        return out;
    }
}
