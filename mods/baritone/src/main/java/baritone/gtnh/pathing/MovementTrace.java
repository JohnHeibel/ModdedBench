// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package baritone.gtnh.pathing;

import java.util.*;

/**
 * A diagnostic ring of the last ticks of movement: what the running movement asked for (inputs, aim) and what the
 * player did (position, ground, collision, crosshair), plus executor events (snags, back-ups, timeouts). Off unless the
 * movementTrace setting is on; nav.status returns it then, when asked with trace:true. Game thread writes, the bridge thread copies.
 */
public final class MovementTrace {
    private static final int SIZE=600;
    private static final Map<String,Object>[] rows=newRows();
    private static int next,count;
    private static long seq;
    private MovementTrace(){}
    @SuppressWarnings("unchecked") private static Map<String,Object>[] newRows(){return new Map[SIZE];}

    public static boolean on(){return baritone.Baritone.settings().movementTrace.value;}
    public static synchronized void add(Map<String,Object> row){
        row.put("n",++seq);rows[next]=row;next=(next+1)%SIZE;count=Math.min(SIZE,count+1);
    }
    public static void event(String kind,Map<String,Object> detail){
        if(!on())return;
        Map<String,Object> row=new LinkedHashMap<>();row.put("event",kind);row.putAll(detail);add(row);
    }
    /** The last `limit` rows, oldest first. */
    public static synchronized List<Map<String,Object>> recent(int limit){
        int n=Math.min(limit,count);List<Map<String,Object>> out=new ArrayList<>(n);
        for(int i=n;i>0;i--)out.add(rows[Math.floorMod(next-i,SIZE)]);
        return out;
    }
    public static synchronized void clear(){Arrays.fill(rows,null);next=count=0;}
    public static double r(double v){return Math.round(v*1000)/1000.0;}
}
