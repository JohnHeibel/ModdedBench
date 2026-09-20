// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh;
import baritone.api.pathing.goals.*;
import baritone.compat.BlockPos;
import java.util.*;
import static baritone.gtnh.pathing.WorkSpec.*;

/** Structured forms construct the source goal classes, including composites. */
final class ReferenceGoals {
    private ReferenceGoals(){}
    static Goal parse(Map<String,Object> spec){return parse(spec,0);}
    private static Goal parse(Map<String,Object> s,int depth){
        if(depth>8)throw new IllegalArgumentException("goal nesting exceeds 8");
        String type=String.valueOf(s.get("type"));
        return switch(type){
            case "block" -> new GoalBlock(position(s));
            case "near" -> new GoalNear(position(s),integer(s,"radius",1,0,64));
            case "adjacent" -> new GoalGetToBlock(position(s));
            case "two_blocks" -> new GoalTwoBlocks(position(s));
            case "xz" -> new GoalXZ(coordinate(s,"x"),coordinate(s,"z"));
            case "y" -> new GoalYLevel(integer(s,"y",64,0,255));
            case "axis" -> new GoalAxis();
            case "inverted" -> new GoalInverted(parse(child(s,"goal"),depth+1));
            case "composite" -> {
                var children=list(s.get("goals"));if(children.isEmpty()||children.size()>64)throw new IllegalArgumentException("composite needs 1..64 goals");
                yield new GoalComposite(children.stream().map(value->parse(map(value),depth+1)).toArray(Goal[]::new));
            }
            case "run_away" -> {
                var from=list(s.get("from"));if(from.isEmpty()||from.size()>64)throw new IllegalArgumentException("run_away needs 1..64 positions");
                yield new GoalRunAway(number(s,"distance",8,1,4096),s.containsKey("maintainY")?integer(s,"maintainY",64,0,255):null,
                    from.stream().map(value->pos(value)).toArray(BlockPos[]::new));
            }
            default -> throw new IllegalArgumentException("unknown source goal type: "+type);
        };
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value){if(!(value instanceof Map<?,?>))throw new IllegalArgumentException("goal must be an object");return (Map<String,Object>)value;}
    private static int coordinate(Map<String,Object> s,String key){if(!s.containsKey(key))throw new IllegalArgumentException(key+" required");return integer(s,key,0,-30000000,30000000);}
    private static BlockPos position(Map<String,Object> s){return pos(s.get("pos"));}
}
