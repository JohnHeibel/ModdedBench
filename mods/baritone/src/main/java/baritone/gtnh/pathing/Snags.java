// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh.pathing;

import java.util.*;

/**
 * Movement edges that failed in this job. A snag is forward held against a collision with the player not moving for
 * TICKS ticks: an inside corner, a lip the plan did not see. The executor backs up to the edge's source and retries it
 * RETRIES times, then bans the edge for the rest of the job, so a re-plan from the same cell cannot hand back the same
 * path. An edge whose movement reports it cannot go on (the player left its cells) is banned on its second failure.
 * The search thread reads the bans; everything else runs on the game thread.
 */
public final class Snags {
    public static final int TICKS=8,RETRIES=2,MAX_BANS=8,NEAR=8,KEPT=32;
    public record Edge(int sx,int sy,int sz,int dx,int dy,int dz){
        List<List<Integer>> points(){return List.of(List.of(sx,sy,sz),List.of(dx,dy,dz));}
    }
    public enum Verdict{RETRY,BAN}
    // Copy-on-write, the newest KEPT: the search scans it per movement without allocating.
    private volatile Edge[] banned=new Edge[0];
    private final Map<Edge,Integer> tries=new HashMap<>();
    private int still;
    // The receipt reads these from the bridge thread.
    private volatile String failure;
    private volatile Map<String,Object> last;

    public void reset(){banned=new Edge[0];tries.clear();still=0;failure=null;last=null;}
    /** One tick of a running movement; true on the tick the player has been snagged for TICKS ticks. */
    public boolean tick(boolean forward,boolean collided,double moved){
        still=forward&&collided&&moved<.01?still+1:0;
        if(still<TICKS)return false;
        still=0;return true;
    }
    /** This edge failed once more: retry it while it has retries left, else ban it. `detail` is what the receipt shows. */
    public Verdict failed(Edge edge,int retries,Map<String,Object> detail){
        int n=tries.merge(edge,1,Integer::sum);
        Map<String,Object> row=new LinkedHashMap<>(detail);row.put("edge",edge.points());row.put("failures",n);last=row;
        if(n<=retries)return Verdict.RETRY;
        if(allows(edge.sx(),edge.sy(),edge.sz(),edge.dx(),edge.dy(),edge.dz())){
            Edge[] more=Arrays.copyOfRange(banned,banned.length<KEPT?0:1,banned.length+1);more[more.length-1]=edge;banned=more;
        }
        // A job that keeps finding new walls in one place is not going to find a way round them: stop with the last one.
        // Walls far apart are a long job's ordinary share, each one walked round.
        int near=0;
        for(Edge e:banned)if(Math.abs(e.sx()-edge.sx())<=NEAR&&Math.abs(e.sy()-edge.sy())<=NEAR&&Math.abs(e.sz()-edge.sz())<=NEAR)near++;
        if(near>MAX_BANS)fail(cause());
        return Verdict.BAN;
    }
    public boolean allows(int sx,int sy,int sz,int dx,int dy,int dz){
        for(Edge e:banned)if(e.sx()==sx&&e.sy()==sy&&e.sz()==sz&&e.dx()==dx&&e.dy()==dy&&e.dz()==dz)return false;
        return true;
    }
    /** The job cannot go on here: backing out failed, or too many edges were banned in one place. The job ends with this cause. */
    public void fail(String cause){if(failure==null)failure=cause;}
    public String failure(){return failure;}
    /** snagged_at_x,y,z (where the player stood) for the last failure, or null when none happened. */
    public String cause(){
        if(last==null)return null;
        Object at=last.get("at");
        return String.valueOf(last.getOrDefault("kind","snagged"))+"_at_"+(at instanceof List<?> p?p.get(0)+","+p.get(1)+","+p.get(2):"unknown");
    }
    public Map<String,Object> status(){
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("banned",Arrays.stream(banned).map(Edge::points).toList());
        out.put("last",last);out.put("failure",failure);return out;
    }
}
