// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package baritone.gtnh.pathing;

import baritone.compat.BlockPos;
import baritone.gtnh.pathing.ClickSpace.Voxel;
import java.util.*;
import java.util.function.Predicate;
import static baritone.gtnh.pathing.WorkSpec.*;

/**
 * What a job's click executor asks off the game thread: which open click cell goes next, and where one click is made
 * from; when from nowhere, what is wrong, what is in the way, and (for a job that may break blocks) the cells whose
 * removal would open a view. Pure: a ClickSpace in, an answer out.
 */
public final class ClickSearch {
    private ClickSearch() {}
    /** step, hides: of a pick. vantages, or the problem with its diagnosis and the cells whose removal would open a view. */
    public record Found(StepPlan.Step step,StepPlan.Step hides,List<Vantages.Vantage> vantages,String problem,Map<String,Object> diagnosis,List<Vantages.Opening> openings) {}
    /**
     * Which open click cell goes next (StepPlan.next) and where it is clicked from. A cell
     * whose remembered ways are all gone is forgotten and the pick made again, so the answer is for the copy as it is.
     */
    public static Found pick(List<StepPlan.Step> open,ClickSpace s,Vantages.Body low,Vantages.Body high,Map<String,List<Vantages.Vantage>> ways,Predicate<BlockPos> removable) {
        for(;;) {
            var p=StepPlan.next(open,s,low,ways);StepPlan.Step c=p.step();
            Found f=look(s,c,p.hides(),c.target(),c.sneak()?low:high,Set.of(),null,p.ready()?null:removable);
            if(!p.ready()||!f.vantages().isEmpty())return f;
            ways.put(c.key(),List.of());
        }
    }
    /**
     * Where one click can be made from, leaving out the stances already tried, else what is wrong and (removable
     * given) the cells whose removal opens a view. breaking: the block this click is to remove; it is not stood on
     * unless there is footing under it.
     */
    public static Found look(ClickSpace s,StepPlan.Step step,StepPlan.Step hides,Vantages.Target target,Vantages.Body body,Set<BlockPos> tried,BlockPos breaking,Predicate<BlockPos> removable) {
        var tally=new Vantages.Tally();
        var found=Vantages.search(s,target,body,8+tried.size(),tally).stream().filter(v->!tried.contains(v.feet())
            &&(breaking==null||!v.feet().equals(ClickSpec.offset(breaking,1))||Double.isFinite(s.with(breaking,Voxel.air()).standingY(breaking)))).limit(8).toList();
        if(!found.isEmpty())return new Found(step,hides,found,null,Map.of(),List.of());
        String problem=Vantages.problem(s,target,tally);
        List<Vantages.Opening> openings=removable==null||problem.equals("support_missing")||problem.equals("hit_not_on_face")?List.of()
            :Vantages.openings(s,target,body,removable,null,3).stream().filter(o->o.remove().size()<=Access.MAX_CELLS).toList();
        return new Found(step,hides,List.of(),problem,diagnosis(s,target,tally),openings);
    }
    /** What a click with no stance is about: the faces that would do and what stands at each, the cells in the way (most often first), and why stances fell away. */
    static Map<String,Object> diagnosis(ClickSpace space,Vantages.Target target,Vantages.Tally tally) {
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("clicks",target.clicks().stream().map(c->Map.of("block",point(c.block()),"face",ClickSpec.NAMES[c.face()],"present",space.at(c.block()).id())).toList());
        List<Map<String,Object>> blocking=new ArrayList<>();
        tally.occluders.entrySet().stream().sorted(Map.Entry.<BlockPos,Integer>comparingByValue().reversed()).limit(3).forEach(e->{
            var v=space.at(e.getKey());Map<String,Object> row=new LinkedHashMap<>();row.put("pos",point(e.getKey()));row.put("id",v.id());row.put("tile",v.tile());row.put("rays",e.getValue());blocking.add(row);});
        out.put("blocking",blocking);
        out.put("rejected",Map.of("noFooting",tally.stand,"outOfReach",tally.reach,"wrongSideOfFace",tally.side,"lineOfSightBlocked",tally.sight,"lookNotAllowed",tally.look,"bodyInPlacedCell",tally.body));
        return out;
    }
}
