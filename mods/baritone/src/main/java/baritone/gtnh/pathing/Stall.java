// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh.pathing;

import java.util.*;

/**
 * The one stall rule for every work job. A job is stalled once, for `limit` of its own ticks, neither its progress
 * measure changed nor the player entered a block it had entered at most once since that measure last changed. New ground
 * rather than distance from one spot: pacing between two places however far apart, or circling, is caught once the
 * ground repeats a second time, and a long walk never is; nor is one walk back out of a dead end, the way it came in.
 * Only ticks the job runs count, so a paused world costs nothing; a new job
 * (a resume included) starts a new watch. A path search in flight is the job working, not stalling: its ticks are excused,
 * up to one search's whole budget per watch, so a re-plan loop (searches back to back on the same ground) still trips, at
 * most one budget later.
 */
public final class Stall {
    public final int limit;
    private long progress;
    private boolean started,advanced;
    private int still,excused,x,y,z;
    // Entries per cell: walking in, not standing there, is what is counted.
    private final Map<Long,Integer> ground=new HashMap<>();
    private long at;
    public Stall(int limit){this.limit=limit;}
    /** One job tick at the player's feet; true once the job has stalled. */
    public boolean tick(long progress,int x,int y,int z){return tick(progress,x,y,z,0);}
    /** As above, with `searchBudget` the ticks the path search in flight may take in all, 0 when none is. */
    public boolean tick(long progress,int x,int y,int z,int searchBudget){
        if(limit<=0)return false;
        this.x=x;this.y=y;this.z=z;
        boolean fresh=!started||progress!=this.progress;
        if(fresh){advanced|=started;started=true;this.progress=progress;ground.clear();}
        long cell=((long)x&0x3FFFFFF)<<38|((long)z&0x3FFFFFF)<<12|(y&0xFFF);
        boolean entered=fresh||cell!=at;at=cell;
        if(entered&&ground.merge(cell,1,Integer::sum)<=2){still=0;excused=0;if(ground.size()>65536){ground.clear();ground.put(cell,1);}return false;}
        if(excused<searchBudget){excused++;return false;}
        return ++still>=limit;
    }
    /** Whether the progress measure moved at all under this watch: a stall then pauses the job rather than failing it. */
    public boolean advanced(){return advanced;}
    public String reason(){return "stalled_no_progress_near_"+x+","+y+","+z;}
    public Map<String,Object> status(){
        Map<String,Object> out=new LinkedHashMap<>();out.put("stallTicks",limit);out.put("ticksWithoutProgress",still);out.put("searchTicksExcused",excused);out.put("groundCells",ground.size());return out;
    }
}
