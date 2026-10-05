// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh;

import baritone.Baritone;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.movement.MovementHelper;
import java.util.*;

/** Why a path job stopped short of its goal, from what the engine measured: the receipt's reason, and its evidence. */
final class PathFailure {
    private PathFailure(){}
    /** On the game thread. `calculationsBefore` is the engine's count when the job started; `detail` gets the evidence. */
    static String cause(Baritone engine,long calculationsBefore,baritone.api.pathing.goals.Goal goal,Map<String,Object> detail){
        var pathing=engine.getPathingBehavior();
        var last=pathing.calculationsStarted()==calculationsBefore?null:pathing.lastCalculation();
        String early=before(engine.snags,last,detail);
        if(early!=null)return early;
        String type=String.valueOf(last.get("type"));
        if(type.equals("EXCEPTION"))return "search_exception";
        if(!type.equals("FAILURE"))return "path_calculation_failed";
        var search=last.get("search") instanceof Map<?,?> m?m:Map.of();
        // Not one movement left the start: the cell the engine started from cannot be stood in or left.
        if(Integer.valueOf(1).equals(search.get("nodes"))&&"exhausted".equals(search.get("why"))&&last.get("start") instanceof List<?> s){
            detail.put("startCells",footing(engine));
            return "invalid_start_at_"+s.get(0)+","+s.get(1)+","+s.get(2);
        }
        Object why=search.get("why");Boolean goalLoaded=null;
        if("unloaded_chunks".equals(why)){
            var world=engine.getPlayerContext().world().nativeWorld;
            goalLoaded=GoalRoom.loaded(goal,(x,y,z)->ForgeSnapshot.loaded(world,x,Math.max(0,Math.min(255,y)),z));
            if(goalLoaded!=null)detail.put("goalLoaded",goalLoaded);
        }
        return refusedBy(searchEnded(why,goalLoaded),search.get("protectedRegions"));
    }
    /**
     * A search that found no path and was refused an edit it was otherwise allowed, in a protected region: the reason
     * names the region. It is what the search met, not proof that the region is all that stands in the way.
     */
    static String refusedBy(String ended,Object regions){
        return regions instanceof List<?> named&&!named.isEmpty()?ended+"; the search was refused edits in protected_region:"+String.join(",",named.stream().map(String::valueOf).toList()):ended;
    }
    /**
     * What ends the job before the search is read: the snag that ended it, or no search having run (`last` null). A snag
     * the job got past (an edge banned, then walked around) is evidence beside the search's own end, not the cause.
     */
    static String before(baritone.gtnh.pathing.Snags snags,Map<String,Object> last,Map<String,Object> detail){
        if(last!=null)detail.put("lastCalculation",last);
        if(snags.failure()!=null||snags.cause()!=null)detail.put("snags",snags.status());
        return snags.failure()!=null?snags.failure():last==null?"stopped_before_searching":null;
    }
    /**
     * A search that returned no path at all, by why its loop ended. Only the reason changes: whatever partial path a search
     * returns is walked as before. Exhausted: every cell reachable from the start was searched. Unloaded chunks: the
     * frontier reached the edge of the chunks the client holds often enough to stop; when the goal itself is in loaded
     * chunks that edge is not where it lies, so the search found no route through the loaded ground it covered.
     */
    static String searchEnded(Object why,Boolean goalLoaded){
        if("exhausted".equals(why))return "no_route_to_goal";
        if("unloaded_chunks".equals(why))return Boolean.TRUE.equals(goalLoaded)?"no_route_in_loaded_chunks":"search_failed_unloaded_chunks";
        return "search_failed_"+(why instanceof String w?w:"unknown");
    }
    /** Each cell under the player's footprint as the engine judges it: what is below, at the feet and at the head. */
    static List<Map<String,Object>> footing(Baritone engine){
        var ctx=engine.getPlayerContext();var bb=ctx.player().boundingBox;var world=ctx.world().nativeWorld;
        List<Map<String,Object>> out=new ArrayList<>();
        for(BetterBlockPos c:baritone.behavior.PathingBehavior.footprint(ctx.playerFeet().y,bb.minX,bb.minZ,bb.maxX,bb.maxZ)){
            Map<String,Object> row=new LinkedHashMap<>();row.put("pos",List.of(c.x,c.y,c.z));
            row.put("below",name(world,c.down()));row.put("feet",name(world,c));row.put("head",name(world,c.up()));
            row.put("floor",MovementHelper.canWalkOn(ctx,c.down()));row.put("clear",MovementHelper.canWalkThrough(ctx,c)&&MovementHelper.canWalkThrough(ctx,c.up()));
            out.add(row);
        }
        return out;
    }
    private static String name(net.minecraft.world.World world,BetterBlockPos p){
        return String.valueOf(net.minecraft.block.Block.blockRegistry.getNameForObject(world.getBlock(p.x,p.y,p.z)));
    }
}
