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
    static String cause(Baritone engine,long calculationsBefore,Map<String,Object> detail){
        var snags=engine.snags;
        if(snags.failure()!=null||snags.anyBanned()){detail.put("snags",snags.status());return snags.failure()!=null?snags.failure():snags.cause();}
        var pathing=engine.getPathingBehavior();
        if(pathing.calculationsStarted()==calculationsBefore)return "stopped_before_searching";
        var last=pathing.lastCalculation();detail.put("lastCalculation",last);
        if(snags.cause()!=null)detail.put("snags",snags.status());
        String type=String.valueOf(last.get("type"));
        if(type.equals("EXCEPTION"))return "search_exception";
        if(!type.equals("FAILURE"))return "path_calculation_failed";
        var search=last.get("search") instanceof Map<?,?> m?m:Map.of();
        // Not one movement left the start: the cell the engine started from cannot be stood in or left.
        if(Integer.valueOf(1).equals(search.get("nodes"))&&"exhausted".equals(search.get("why"))&&last.get("start") instanceof List<?> s){
            detail.put("startCells",footing(engine));
            return "invalid_start_at_"+s.get(0)+","+s.get(1)+","+s.get(2);
        }
        return "search_failed_"+(search.get("why") instanceof String why?why:"unknown");
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
