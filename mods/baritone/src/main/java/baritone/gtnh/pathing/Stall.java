// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh.pathing;

import java.util.*;

/**
 * The one stall rule for every work job. A job is stalled once, for `limit` of its own ticks, neither its progress
 * measure changed nor the player stood in a block it had not stood in since that measure last changed. New ground
 * rather than distance from one spot: pacing between two places however far apart, or circling, is caught once the
 * ground repeats, and a long walk never is. Only ticks the job runs count, so a paused world costs nothing; a new job
 * (a resume included) starts a new watch.
 */
public final class Stall {
    public final int limit;
    private long progress;
    private boolean started,advanced;
    private int still,x,y,z;
    private final Set<Long> ground=new HashSet<>();
    public Stall(int limit){this.limit=limit;}
    /** One job tick at the player's feet; true once the job has stalled. */
    public boolean tick(long progress,int x,int y,int z){
        if(limit<=0)return false;
        this.x=x;this.y=y;this.z=z;
        if(!started||progress!=this.progress){advanced|=started;started=true;this.progress=progress;ground.clear();}
        long cell=((long)x&0x3FFFFFF)<<38|((long)z&0x3FFFFFF)<<12|(y&0xFFF);
        if(ground.add(cell)){still=0;if(ground.size()>65536){ground.clear();ground.add(cell);}return false;}
        return ++still>=limit;
    }
    /** Whether the progress measure moved at all under this watch: a stall then pauses the job rather than failing it. */
    public boolean advanced(){return advanced;}
    public String reason(){return "stalled_no_progress_near_"+x+","+y+","+z;}
    public Map<String,Object> status(){
        Map<String,Object> out=new LinkedHashMap<>();out.put("stallTicks",limit);out.put("ticksWithoutProgress",still);out.put("groundCells",ground.size());return out;
    }
}
