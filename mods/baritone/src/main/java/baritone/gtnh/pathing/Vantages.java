// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package baritone.gtnh.pathing;

import baritone.compat.BlockPos;
import baritone.gtnh.pathing.ClickSpec.Vec;
import baritone.gtnh.pathing.ClickSpace.Voxel;
import java.util.*;
import java.util.function.Predicate;

/**
 * Where a player can stand to make one click: a cell with footing and room for the body, whose eye is within reach of
 * the hit point, on the outer side of the clicked face, with nothing in the way, and whose look there satisfies the
 * click's look requirement. When there is none, what is wrong, and which cells would open one if they were removed.
 * Pure geometry over a ClickSpace; the game's own ray decides at the moment of the click.
 */
public final class Vantages {
    private Vantages() {}
    /** One way to make the click: this face of this block. */
    public record Click(BlockPos block,int face) {}
    /** clicks: the acceptable (block, face) pairs; hit: block-local point or null (sampled); occupied: the cell a placement fills, or null. */
    public record Target(List<Click> clicks,Vec hit,ClickSpec.Look look,BlockPos occupied) {}
    public record Body(double eyeHeight,double reach) {}
    public record Vantage(BlockPos feet,double standingY,Vec eye,Click click,Vec point,double yaw,double pitch) {}
    public record Opening(BlockPos feet,Click click,Vec point,List<BlockPos> remove) {}
    /** Targets for a placement into cell `into`: click face f of the block behind it (face null: any face). */
    public static List<Click> placing(BlockPos into,Integer face) {
        List<Click> out=new ArrayList<>();
        for(int f=0;f<6;f++)if(face==null||face==f)out.add(new Click(ClickSpec.offset(into,ClickSpec.opposite(f)),f));
        return out;
    }
    public static List<Click> using(BlockPos block,Integer face) {
        List<Click> out=new ArrayList<>();for(int f=0;f<6;f++)if(face==null||face==f)out.add(new Click(block,f));return out;
    }
    /** A clickable block: something a ray stops at that is not itself replaced by a placement. */
    public static boolean clickable(ClickSpace s,BlockPos p){Voxel v=s.at(p);return v.kind()!=TerrainGrid.UNKNOWN&&!v.replaceable()&&!v.selection().isEmpty();}
    static CollisionBox union(List<CollisionBox> boxes) {
        double a=1e9,b=1e9,c=1e9,d=-1e9,e=-1e9,f=-1e9;
        for(CollisionBox x:boxes){a=Math.min(a,x.minX());b=Math.min(b,x.minY());c=Math.min(c,x.minZ());d=Math.max(d,x.maxX());e=Math.max(e,x.maxY());f=Math.max(f,x.maxZ());}
        return new CollisionBox(a,b,c,d,e,f);
    }
    static double plane(CollisionBox u,int face){return switch(face){case 0->u.minY();case 1->u.maxY();case 2->u.minZ();case 3->u.maxZ();case 4->u.minX();default->u.maxX();};}
    static int axis(int face){return face<2?1:face<4?2:0;}
    static double get(Vec v,int axis){return axis==0?v.x():axis==1?v.y():v.z();}
    /** The points on the face to aim at: the given hit (its coordinate along the face normal set onto the face), or the centre and four inset points. */
    public static List<Vec> points(ClickSpace s,Click c,Vec hit) {
        Voxel v=s.at(c.block());if(v.selection().isEmpty())return List.of();
        CollisionBox u=union(v.selection());int a=axis(c.face());double p=plane(u,c.face());
        double[] lo={u.minX(),u.minY(),u.minZ()},hi={u.maxX(),u.maxY(),u.maxZ()};
        List<Vec> out=new ArrayList<>();
        if(hit!=null) {
            double[] h={c.block().getX()+hit.x(),c.block().getY()+hit.y(),c.block().getZ()+hit.z()};h[a]=p;
            for(int i=0;i<3;i++)if(i!=a&&(h[i]<lo[i]-1e-3||h[i]>hi[i]+1e-3))return List.of();
            out.add(new Vec(h[0],h[1],h[2]));return out;
        }
        double[][] fractions={{.5,.5},{.25,.25},{.75,.25},{.25,.75},{.75,.75}};
        for(double[] fr:fractions) {
            double[] h=new double[3];int k=0;
            for(int i=0;i<3;i++){if(i==a){h[i]=p;continue;}h[i]=lo[i]+(hi[i]-lo[i])*fr[k++];}
            out.add(new Vec(h[0],h[1],h[2]));
        }
        return out;
    }
    public static double yaw(Vec eye,Vec to){return Math.toDegrees(Math.atan2(-(to.x()-eye.x()),to.z()-eye.z()));}
    public static double pitch(Vec eye,Vec to){double dx=to.x()-eye.x(),dz=to.z()-eye.z();return -Math.toDegrees(Math.atan2(to.y()-eye.y(),Math.sqrt(dx*dx+dz*dz)));}
    static boolean outward(Vec eye,Vec point,int face){
        int a=axis(face);double e=get(eye,a),p=get(point,a);return face%2==0?e<p-1e-3:e>p+1e-3;
    }
    /** Cells whose selection shape the segment eye -> point passes through, in order, skipping `skip`. all=false stops at the first. */
    static List<BlockPos> occluders(ClickSpace s,Vec eye,Vec point,Set<BlockPos> skip,boolean all) {
        List<BlockPos> out=new ArrayList<>();
        double dx=point.x()-eye.x(),dy=point.y()-eye.y(),dz=point.z()-eye.z();
        int x=(int)Math.floor(eye.x()),y=(int)Math.floor(eye.y()),z=(int)Math.floor(eye.z());
        int ex=(int)Math.floor(point.x()),ey=(int)Math.floor(point.y()),ez=(int)Math.floor(point.z());
        int sx=dx>0?1:dx<0?-1:0,sy=dy>0?1:dy<0?-1:0,sz=dz>0?1:dz<0?-1:0;
        double tx=sx==0?Double.POSITIVE_INFINITY:((sx>0?x+1-eye.x():eye.x()-x)/Math.abs(dx)),ddx=sx==0?Double.POSITIVE_INFINITY:1/Math.abs(dx);
        double ty=sy==0?Double.POSITIVE_INFINITY:((sy>0?y+1-eye.y():eye.y()-y)/Math.abs(dy)),ddy=sy==0?Double.POSITIVE_INFINITY:1/Math.abs(dy);
        double tz=sz==0?Double.POSITIVE_INFINITY:((sz>0?z+1-eye.z():eye.z()-z)/Math.abs(dz)),ddz=sz==0?Double.POSITIVE_INFINITY:1/Math.abs(dz);
        for(int guard=0;guard<64;guard++) {
            BlockPos p=new BlockPos(x,y,z);
            if(!skip.contains(p)) {
                Voxel v=s.at(p);
                boolean hit=v.kind()==TerrainGrid.UNKNOWN;
                if(!hit)for(CollisionBox b:v.selection())if(segment(b,eye,dx,dy,dz)){hit=true;break;}
                if(hit){out.add(p);if(!all)return out;}
            }
            if(x==ex&&y==ey&&z==ez)break;
            if(tx<=ty&&tx<=tz){if(tx>1)break;x+=sx;tx+=ddx;}
            else if(ty<=tz){if(ty>1)break;y+=sy;ty+=ddy;}
            else {if(tz>1)break;z+=sz;tz+=ddz;}
        }
        return out;
    }
    /** Does the segment eye + t*(d) for t in [0, 1-eps) pass through the inside of box b? */
    static boolean segment(CollisionBox b,Vec o,double dx,double dy,double dz) {
        double t0=0,t1=1-1e-4;
        double[] origin={o.x(),o.y(),o.z()},dir={dx,dy,dz},lo={b.minX(),b.minY(),b.minZ()},hi={b.maxX(),b.maxY(),b.maxZ()};
        for(int i=0;i<3;i++) {
            if(Math.abs(dir[i])<1e-12){if(origin[i]<=lo[i]+1e-6||origin[i]>=hi[i]-1e-6)return false;continue;}
            double a=(lo[i]-origin[i])/dir[i],c=(hi[i]-origin[i])/dir[i];
            if(a>c){double t=a;a=c;c=t;}
            t0=Math.max(t0,a);t1=Math.min(t1,c);if(t0>=t1-1e-9)return false;
        }
        return true;
    }
    static boolean bodyHits(BlockPos feet,double y,BlockPos cell) {
        if(cell==null)return false;
        CollisionBox body=new CollisionBox(feet.getX()+.2,y+.001,feet.getZ()+.2,feet.getX()+.8,y+1.799,feet.getZ()+.8);
        return body.intersects(new CollisionBox(cell.getX(),cell.getY(),cell.getZ(),cell.getX()+1,cell.getY()+1,cell.getZ()+1));
    }
    /** Every feet cell whose box around the clicked block a reach can span, nearest the clicked block first. */
    static List<BlockPos> candidates(Click c,Body b) {
        int r=(int)Math.ceil(b.reach())+1;BlockPos t=c.block();List<BlockPos> out=new ArrayList<>();
        for(int x=t.getX()-r;x<=t.getX()+r;x++)for(int z=t.getZ()-r;z<=t.getZ()+r;z++)for(int y=t.getY()-r-2;y<=t.getY()+r;y++) {
            double ex=x+.5-(t.getX()+.5),ez=z+.5-(t.getZ()+.5),ey=y+b.eyeHeight()-(t.getY()+.5);
            if(ex*ex+ey*ey+ez*ez<=(b.reach()+1.5)*(b.reach()+1.5))out.add(new BlockPos(x,y,z));
        }
        out.sort(Comparator.comparingDouble(p->sq(p,t)));
        return out;
    }
    static double sq(BlockPos a,BlockPos b){double x=a.getX()-b.getX(),y=a.getY()-b.getY(),z=a.getZ()-b.getZ();return x*x+y*y+z*z;}
    /** What failed, counted over every candidate: the answer to "why is there no vantage". */
    public static final class Tally {public int stand,reach,side,sight,look,body;public Map<BlockPos,Integer> occluders=new HashMap<>();}
    public static List<Vantage> search(ClickSpace s,Target t,Body b,int limit){return search(s,t,b,limit,null);}
    public static List<Vantage> search(ClickSpace s,Target t,Body b,int limit,Tally tally) {
        List<Vantage> out=new ArrayList<>();
        for(Click c:t.clicks()) {
            // A search belongs to a job: when that ends, the thread searching for it is interrupted and stops here.
            if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("click search cancelled");
            if(!clickable(s,c.block()))continue;
            List<Vec> points=points(s,c,t.hit());if(points.isEmpty())continue;
            Set<BlockPos> skip=new HashSet<>();skip.add(c.block());if(t.occupied()!=null)skip.add(t.occupied());
            for(BlockPos feet:candidates(c,b)) {
                if(feet.equals(t.occupied()))continue;
                double y=s.standingY(feet);
                if(!Double.isFinite(y)){if(tally!=null)tally.stand++;continue;}
                if(bodyHits(feet,y,t.occupied())){if(tally!=null)tally.body++;continue;}
                Vec eye=new Vec(feet.getX()+.5,y+b.eyeHeight(),feet.getZ()+.5);
                for(Vec point:points) {
                    if(eye.distance(point)>b.reach()-.05){if(tally!=null)tally.reach++;continue;}
                    if(!outward(eye,point,c.face())){if(tally!=null)tally.side++;continue;}
                    var blocked=occluders(s,eye,point,skip,false);
                    if(!blocked.isEmpty()){if(tally!=null){tally.sight++;tally.occluders.merge(blocked.get(0),1,Integer::sum);}continue;}
                    double yaw=yaw(eye,point),pitch=pitch(eye,point);
                    if(!t.look().accepts(yaw,pitch)){if(tally!=null)tally.look++;continue;}
                    out.add(new Vantage(feet,y,eye,c,point,yaw,pitch));
                    if(out.size()>=limit)return out;
                    break;
                }
            }
        }
        return out;
    }
    /**
     * Whether this way of making a click is still one after a full block goes into `cell`: the body does not stand in
     * it and the look does not pass through it. Exact, since a block added elsewhere takes no footing away.
     */
    public static boolean survives(Vantage v,BlockPos cell) {
        Vec e=v.eye(),p=v.point();
        return !bodyHits(v.feet(),v.standingY(),cell)&&!segment(new CollisionBox(cell.getX(),cell.getY(),cell.getZ(),cell.getX()+1,cell.getY()+1,cell.getZ()+1),e,p.x()-e.x(),p.y()-e.y(),p.z()-e.z());
    }
    /** Why search found nothing: support_missing, hit_not_on_face, look_unreachable (a view exists, not with the look asked for) or no_vantage. */
    public static String problem(ClickSpace s,Target t,Tally tally) {
        if(t.clicks().stream().noneMatch(c->clickable(s,c.block())))return "support_missing";
        if(t.clicks().stream().filter(c->clickable(s,c.block())).allMatch(c->points(s,c,t.hit()).isEmpty()))return "hit_not_on_face";
        if(tally!=null&&tally.look>0)return "look_unreachable";
        return "no_vantage";
    }
    /**
     * The smallest sets of cells whose removal would open a vantage: a feet cell above a full block, its body cells and
     * the cells in the line of sight. removable says which cells may be taken (never the clicked block, the cell placed
     * into, or the floor). Distinct sets, fewest cells first, then nearest `near`.
     */
    public static List<Opening> openings(ClickSpace s,Target t,Body b,Predicate<BlockPos> removable,BlockPos near,int limit) {
        List<Opening> found=new ArrayList<>();
        for(Click c:t.clicks()) {
            if(!clickable(s,c.block()))continue;
            List<Vec> points=points(s,c,t.hit());
            for(BlockPos feet:candidates(c,b)) {
                BlockPos floor=new BlockPos(feet.getX(),feet.getY()-1,feet.getZ()),head=new BlockPos(feet.getX(),feet.getY()+1,feet.getZ());
                if(feet.equals(t.occupied())||head.equals(t.occupied())||feet.equals(c.block())||head.equals(c.block()))continue;
                if(s.at(floor).kind()!=TerrainGrid.SUPPORT||bodyHits(feet,feet.getY(),t.occupied()))continue;
                Vec eye=new Vec(feet.getX()+.5,feet.getY()+b.eyeHeight(),feet.getZ()+.5);
                for(Vec point:points) {
                    if(eye.distance(point)>b.reach()-.05||!outward(eye,point,c.face())||!t.look().accepts(yaw(eye,point),pitch(eye,point)))continue;
                    LinkedHashSet<BlockPos> remove=new LinkedHashSet<>();
                    for(BlockPos body:List.of(feet,head))if(s.at(body).kind()!=TerrainGrid.CLEAR)remove.add(body);
                    Set<BlockPos> skip=new HashSet<>(remove);skip.add(c.block());if(t.occupied()!=null)skip.add(t.occupied());
                    remove.addAll(occluders(s,eye,point,skip,true));
                    if(remove.isEmpty()||remove.contains(floor)||!remove.stream().allMatch(removable))continue;
                    found.add(new Opening(feet,c,point,List.copyOf(remove)));
                    break;
                }
            }
        }
        BlockPos ref=near!=null?near:t.clicks().isEmpty()?new BlockPos(0,0,0):t.clicks().get(0).block();
        found.sort(Comparator.comparingInt((Opening o)->o.remove().size()).thenComparingDouble(o->sq(o.feet(),ref)));
        List<Opening> out=new ArrayList<>();Set<Set<BlockPos>> seen=new HashSet<>();
        for(Opening o:found)if(seen.add(Set.copyOf(o.remove()))){out.add(o);if(out.size()>=limit)break;}
        return out;
    }
}
