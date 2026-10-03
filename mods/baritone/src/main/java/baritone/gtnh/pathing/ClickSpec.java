// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package baritone.gtnh.pathing;

import baritone.compat.BlockPos;
import java.util.*;
import static baritone.gtnh.pathing.WorkSpec.*;

/**
 * How one right click is made: the clicked face, the hit point on it (block-local), what the look must be and whether
 * the player sneaks. Every field is optional; an omitted one leaves the choice to the builder. Pure data: no game classes.
 * Faces use the game's indices 0 down, 1 up, 2 north (-z), 3 south (+z), 4 west (-x), 5 east (+x).
 */
public record ClickSpec(Integer face,Vec hit,Look look,Boolean sneak) {
    public static final ClickSpec ANY=new ClickSpec(null,null,Look.ANY,null);
    public static final String[] NAMES={"down","up","north","south","west","east"};
    public static final int[][] DIR={{0,-1,0},{0,1,0},{0,0,-1},{0,0,1},{-1,0,0},{1,0,0}};
    public record Vec(double x,double y,double z) {
        public Vec add(double dx,double dy,double dz){return new Vec(x+dx,y+dy,z+dz);}
        public double distance(Vec o){return Math.sqrt((x-o.x)*(x-o.x)+(y-o.y)*(y-o.y)+(z-o.z)*(z-o.z));}
        public List<Double> list(){return List.of(x,y,z);}
    }
    /**
     * A requirement on the look at the moment of the click. toward: a direction the look must point at (north, south,
     * east, west compare the horizontal quadrant of yaw, as the game's player facing does; up and down mean pitch at most
     * -45 or at least 45). yaw and pitch: inclusive ranges in degrees (the game's convention: yaw 0 looks south, 90 west;
     * pitch 90 looks straight down). All given parts must hold.
     */
    public record Look(Integer toward,double[] yaw,double[] pitch) {
        public static final Look ANY=new Look(null,null,null);
        public boolean any(){return toward==null&&yaw==null&&pitch==null;}
        public boolean accepts(double yawDeg,double pitchDeg) {
            if(yaw!=null){double y=norm(yawDeg-yaw[0]);if(y>yaw[1]-yaw[0]+1e-6)return false;}
            if(pitch!=null&&(pitchDeg<pitch[0]-1e-6||pitchDeg>pitch[1]+1e-6))return false;
            if(toward!=null) {
                if(toward==0)return pitchDeg>=45;
                if(toward==1)return pitchDeg<=-45;
                int quadrant=(int)Math.floor(yawDeg*4/360+.5)&3;
                return new int[]{3,4,2,5}[quadrant]==toward;
            }
            return true;
        }
        public Map<String,Object> json() {
            Map<String,Object> out=new LinkedHashMap<>();
            if(toward!=null)out.put("toward",NAMES[toward]);
            if(yaw!=null)out.put("yaw",List.of(yaw[0],yaw[1]));
            if(pitch!=null)out.put("pitch",List.of(pitch[0],pitch[1]));
            return out;
        }
        @Override public boolean equals(Object o){return o instanceof Look l&&Objects.equals(toward,l.toward)&&Arrays.equals(yaw,l.yaw)&&Arrays.equals(pitch,l.pitch);}
        @Override public int hashCode(){return Objects.hash(toward,Arrays.hashCode(yaw),Arrays.hashCode(pitch));}
    }
    static double norm(double degrees){double d=degrees%360;return d<0?d+360:d;}
    public static int face(Object value) {
        if(value instanceof Number n){int f=integer(Map.of("face",n),"face",0,0,5);return f;}
        if(value instanceof String s) {
            String k=s.toLowerCase(Locale.ROOT);
            for(int i=0;i<6;i++)if(NAMES[i].equals(k))return i;
            int i=List.of("-y","+y","-z","+z","-x","+x").indexOf(k);if(i>=0)return i;
        }
        throw new IllegalArgumentException("face is 0..5 or one of down, up, north, south, west, east (or -y, +y, -z, +z, -x, +x)");
    }
    public static int opposite(int face){return face^1;}
    public static BlockPos offset(BlockPos p,int face){return new BlockPos(p.getX()+DIR[face][0],p.getY()+DIR[face][1],p.getZ()+DIR[face][2]);}
    static double[] range(Object value,String key,double min,double max) {
        if(value instanceof Number n){double v=number(Map.of(key,n),key,0,min,max);return new double[]{v,v};}
        List<?> r=list(value);if(r.size()!=2)throw new IllegalArgumentException(key+" is a number or [low, high]");
        double lo=number(Map.of(key,r.get(0)),key,0,min,max),hi=number(Map.of(key,r.get(1)),key,0,min,max);
        if(hi<lo)throw new IllegalArgumentException(key+" range needs low <= high");
        return new double[]{lo,hi};
    }
    /** Parse {face?, hit?:[x,y,z], look?:{toward?,yaw?,pitch?}, sneak?}. */
    public static ClickSpec parse(Map<String,Object> m) {
        fields(m,Set.of("face","hit","look","sneak"));
        Integer face=m.containsKey("face")?face(m.get("face")):null;
        Vec hit=null;
        if(m.containsKey("hit")) {
            List<?> h=list(m.get("hit"));if(h.size()!=3)throw new IllegalArgumentException("hit needs three block-local coordinates");
            double[] v=new double[3];for(int i=0;i<3;i++)v[i]=number(Map.of("hit",h.get(i)),"hit",0,-1,2);
            hit=new Vec(v[0],v[1],v[2]);
        }
        Look look=Look.ANY;
        if(m.containsKey("look")) {
            var l=object(m.get("look"));fields(l,Set.of("toward","yaw","pitch"));
            look=new Look(l.containsKey("toward")?face(l.get("toward")):null,l.containsKey("yaw")?range(l.get("yaw"),"yaw",-720,720):null,l.containsKey("pitch")?range(l.get("pitch"),"pitch",-90,90):null);
            if(look.yaw!=null&&look.yaw[1]-look.yaw[0]>=360)look=new Look(look.toward,null,look.pitch);
        }
        Boolean sneak=m.containsKey("sneak")?bool(m,"sneak",false):null;
        return new ClickSpec(face,hit,look,sneak);
    }
    public Map<String,Object> json() {
        Map<String,Object> out=new LinkedHashMap<>();
        if(face!=null)out.put("face",face);
        if(hit!=null)out.put("hit",hit.list());
        if(!look.any())out.put("look",look.json());
        if(sneak!=null)out.put("sneak",sneak);
        return out;
    }
    /** Whether the click fixes something the game may keep outside block and metadata (a facing or a side in a tile entity). */
    public boolean orients(){return face!=null||hit!=null||!look.any();}
}
