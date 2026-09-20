// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh;

import baritone.gtnh.pathing.*;
import static baritone.gtnh.pathing.WorkSpec.*;
import java.util.*;
import net.minecraft.block.Block;
import net.minecraft.item.*;

/** Sparse restricted builder: DJ2 preflight, live diff, attempt ledger and final verification. */
final class BuildingProcess extends BulkJob {
    final List<Cell> cells;
    final boolean replace;
    final Map<String,Object> attempts;
    final Set<String> failedPoses=new HashSet<>();
    final List<Map<String,Object>> failures=new ArrayList<>();
    Map<String,Object> inspection;
    Cell target;
    int placed,removed,workGoalCount,cellPage,nextCellPage,routeRetries,omittedCells;
    String phase="preflight";
    @Override String phase(){return phase;}
    BuildingProcess(BaritoneNavigation nav,WorkJournal journal,Map<String,Object> options) {
        super(nav,journal,options);replace=bool(params,"replaceExisting",false);
        List<Cell> all=validatedCells(params);List<Cell> selected=new ArrayList<>();
        if(journal.progress.containsKey("selected")) {
            Set<BlockPos> positions=new HashSet<>();for(Object p:list(journal.progress.get("selected")))positions.add(pos(p));for(Cell c:all)if(positions.contains(c.pos()))selected.add(c);
        }else {
            for(Cell c:all)if(c.replace().isEmpty()||WorkAccess.block(world,c.pos(),c.replace()))selected.add(c);
            journal.progress.put("selected",selected.stream().map(c->point(c.pos())).toList());
        }
        cells=List.copyOf(selected);for(Cell c:cells)if(!c.clear())block(c);
        attempts=new LinkedHashMap<>(child(journal.progress,"attempts"));journal.progress.put("attempts",attempts);
        placed=integer(journal.progress,"placed",0,0,1000000);removed=integer(journal.progress,"removed",0,0,1000000);
    }
    static List<Cell> validatedCells(Map<String,Object> params) {
        var cells=WorkSpec.cells(params);
        for(Cell cell:cells) {
            if(!cell.replace().isEmpty())WorkAccess.validateBlockSelector(cell.replace());
            if(!cell.item().isEmpty())WorkAccess.validateItemSelector(cell.item());
            if(cell.verify().containsKey("pickedItem"))WorkAccess.validateItemSelector(child(cell.verify(),"pickedItem"));
        }
        return cells;
    }
    static Block block(Cell c) {
        Object value=Block.blockRegistry.getObject(c.id());if(!(value instanceof Block b)||!c.id().equals(Block.blockRegistry.getNameForObject(b)))throw new IllegalArgumentException("unknown block "+c.id());return b;
    }
    static boolean matches(Cell c) {var world=WorkAccess.MC.theWorld;return ForgeSnapshot.loaded(world,c.pos().x(),c.pos().y(),c.pos().z())&&(c.clear()?world.isAirBlock(c.pos().x(),c.pos().y(),c.pos().z()):world.getBlock(c.pos().x(),c.pos().y(),c.pos().z())==block(c)&&world.getBlockMetadata(c.pos().x(),c.pos().y(),c.pos().z())==c.meta())&&verified(c);}
    static boolean verified(Cell c){return !c.verify().containsKey("pickedItem")||WorkAccess.item(WorkAccess.picked(WorkAccess.MC.theWorld,c.pos()),child(c.verify(),"pickedItem"));}
    static Map<String,Object> material(Cell c) {
        if(!c.item().isEmpty()){WorkAccess.validateItemSelector(c.item());return c.item();}
        Block block=block(c);Item item=Item.getItemFromBlock(block);
        if(item==null)throw new IllegalArgumentException("no native item mapping for "+c.id()+"; provide item selector and placement adapter");
        return Map.of("id",Item.itemRegistry.getNameForObject(item),"meta",block.damageDropped(c.meta()));
    }
    static int slot(Cell c) {
        Map<String,Object> selector=material(c);int held=WorkAccess.MC.thePlayer.inventory.currentItem;
        if(WorkAccess.item(WorkAccess.MC.thePlayer.getHeldItem(),selector))return held;
        for(int i=0;i<36;i++)if(WorkAccess.item(WorkAccess.MC.thePlayer.inventory.mainInventory[i],selector))return i;return -1;
    }
    static Map<String,Object> inspect(List<Cell> cells,boolean replace,boolean override) {
        var mc=WorkAccess.MC;Map<Map<String,Object>,Integer> required=new LinkedHashMap<>();List<Map<String,Object>> differences=new ArrayList<>();
        int correct=0,unloaded=0,conflicts=0,protectedCount=0,unsupported=0;
        for(Cell c:cells) {
            String reason="different";if(matches(c)){correct++;continue;}
            if(!ForgeSnapshot.loaded(mc.theWorld,c.pos().x(),c.pos().y(),c.pos().z())){unloaded++;reason="unloaded";}
            else {
                Block actual=mc.theWorld.getBlock(c.pos().x(),c.pos().y(),c.pos().z());
                if(!replace&&!actual.isAir(mc.theWorld,c.pos().x(),c.pos().y(),c.pos().z())&&!actual.isReplaceable(mc.theWorld,c.pos().x(),c.pos().y(),c.pos().z())){conflicts++;reason="occupied";}
                String protection=WorkAccess.protection(c.pos(),override);if(protection!=null){protectedCount++;reason=protection;}
            }
            if(!c.clear())try{required.merge(material(c),1,Integer::sum);}catch(IllegalArgumentException e){unsupported++;reason=e.getMessage();}
            if(differences.size()<64) {
                Map<String,Object> expected=new LinkedHashMap<>(c.clear()?Map.of("clear",true):Map.of("id",c.id(),"meta",c.meta()));Map<String,Object> actual=WorkAccess.observed(mc.theWorld,c.pos());
                if(!c.verify().isEmpty()){expected.put("verify",c.verify());if(ForgeSnapshot.loaded(mc.theWorld,c.pos().x(),c.pos().y(),c.pos().z()))actual.put("pickedItem",InventorySelection.describe(WorkAccess.picked(mc.theWorld,c.pos())));}
                differences.add(Map.of("pos",point(c.pos()),"expected",expected,"actual",actual,"reason",reason));
            }
        }
        int[] available=new int[36];for(int i=0;i<36;i++)if(mc.thePlayer.inventory.mainInventory[i]!=null)available[i]=mc.thePlayer.inventory.mainInventory[i].stackSize;
        List<Map<String,Object>> materials=new ArrayList<>();int missing=0;
        // Allocate from a shared inventory budget. Overlapping selectors must not
        // each claim the same stack, as independent per-material counts would.
        var ordered=new ArrayList<>(required.entrySet());ordered.sort(Comparator.comparingInt((Map.Entry<Map<String,Object>,Integer> e)->-e.getKey().size()));
        for(var e:ordered){int need=e.getValue(),supplied=0;for(int i=0;i<36&&supplied<need;i++)if(WorkAccess.item(mc.thePlayer.inventory.mainInventory[i],e.getKey())){int take=Math.min(need-supplied,available[i]);available[i]-=take;supplied+=take;}missing+=need-supplied;materials.add(Map.of("selector",e.getKey(),"needed",need,"allocated",supplied,"missing",need-supplied));}
        Map<String,Object> out=new LinkedHashMap<>();out.put("total",cells.size());out.put("correct",correct);out.put("mismatched",cells.size()-correct);out.put("matches",correct==cells.size());out.put("unloaded",unloaded);out.put("conflicts",conflicts);out.put("protected",protectedCount);out.put("unsupported",unsupported);out.put("missingItems",missing);out.put("materials",materials);out.put("differences",differences);out.put("differencesTruncated",cells.size()-correct>differences.size());out.put("source","client_world");out.put("materialEstimate","one selected item per unmatched cell; native multi-block/consumable behavior needs explicit adapter");return out;
    }
    static Map<String,Object> preview(Map<String,Object> params) {WorkAccess.player();var all=validatedCells(params);var selected=all.stream().filter(c->c.replace().isEmpty()||WorkAccess.block(WorkAccess.MC.theWorld,c.pos(),c.replace())).toList();return inspect(selected,bool(params,"replaceExisting",false),bool(params,"overrideProtection",false));}
    @Override void step() {
        if(phase.equals("preflight")) {
            inspection=inspect(cells,replace,override);
            for(String key:List.of("unloaded","conflicts","protected","unsupported","missingItems"))if(((Number)inspection.get(key)).intValue()>0){finish("failed","preflight_"+key);return;}
            phase="choose";return;
        }
        if(phase.equals("moving")) {
            if(child!=null){boolean ok=child.succeeded();var receipt=consumeChild();if(!ok){
                if(failures.size()<128)failures.add(Map.of("reason","work_goals_unreachable","detail",receipt));
                String why=String.valueOf(receipt.get("reason"));
                if(why.contains("damage")||why.contains("hazard")||why.contains("superseded")||why.contains("death")||why.contains("world_changed")){finish("failed",why);return;}
                // Re-observe a disrupted route before abandoning this frontier.
                // A genuinely unreachable frontier advances to later cells;
                // a large selection must not starve behind its nearest subset.
                if(why.equals("unreachable")||routeRetries++>=2){cellPage=nextCellPage;routeRetries=0;}
                phase="choose";return;
            }}
            phase="choose";
        }
        if(phase.equals("working")) {
            if(child!=null) {
                boolean ok=child.succeeded(),placement=child instanceof ExactPlacementJob;var receipt=consumeChild();
                if(!ok) {
                    if(placement&&Boolean.TRUE.equals(receipt.get("inputDelivered"))){inspection=inspect(cells,replace,override);finish("failed","placement_not_verified_inspect_before_retry");return;}
                    reject("work_failed",receipt);return;
                }
                if(placement){placed++;journal.progress.put("placed",placed);}else{removed++;journal.progress.put("removed",removed);}
                journal.save(status());phase="choose";failedPoses.clear();cellPage=0;routeRetries=0;return;
            }
            if(matches(target)){phase="choose";return;}
            BlockPos p=target.pos();Block current=world.getBlock(p.x(),p.y(),p.z());String protection=WorkAccess.protection(p,override);
            if(protection!=null){finish("failed",protection);return;}
            boolean occupied=!current.isAir(world,p.x(),p.y(),p.z())&&!current.isReplaceable(world,p.x(),p.y(),p.z());
            try {
                if(occupied||target.clear()) {
                    if(!replace){finish("failed","occupied_target_changed");return;}
                    if(attempts.containsKey(key(target))){finish("failed","attempted_placement_changed_no_destructive_retry");return;}
                    child=new MiningJob(mc,p,Math.min(remaining,6000),true,false,lease,override);state="clearing";
                } else {
                    int count=integer(attempts,key(target),0,0,2);if(count>=2){finish("failed","placement_attempt_limit");return;}
                    child=new ExactPlacementJob(target,slot(target),Math.min(remaining,200),lease,()->{journal.recordAttempt(key(target),count+1);attempts.put(key(target),count+1);journal.save(status());});state="placing";
                }
            }catch(IllegalArgumentException error){reject(error.getMessage(),Map.of());}
            return;
        }
        if(phase.equals("choose")) {
            List<Cell> pending=cells.stream().filter(c->!matches(c)).toList();
            if(!pending.isEmpty()) {
                Cell first=pending.get(0);
                // BuilderProcess first exhausts work reachable from the current
                // pose. Preserve clear/build layer order while avoiding a trip
                // to the first coordinate when another cell is already in reach.
                var layer=pending.stream().filter(c->c.clear()==first.clear()&&c.pos().y()==first.pos().y()).sorted(Comparator.comparingDouble(c->WorkAccess.distance(c.pos()))).toList();
                target=layer.stream().filter(c->!failedPoses.contains(poseKey(c,WorkAccess.feet()))&&reachableNow(c)).findFirst().orElse(null);
                if(target!=null){phase="working";return;}
                // Upstream BuilderProcess.assemble supplies a GoalComposite:
                // let path cost choose among useful work positions, rather than
                // serially pathing to arbitrary neighbours of the first cell.
                Set<BlockPos> goals=new LinkedHashSet<>();
                nextCellPage=cellPage;
                for(int i=cellPage;i<Math.min(layer.size(),cellPage+64);i++) {
                    Cell cell=layer.get(i);
                    int accepted=0;
                    for(WorkAccess.Pose pose:WorkAccess.buildingApproaches(world,cell.pos())) {
                        if(pose.feet().equals(WorkAccess.feet())||failedPoses.contains(poseKey(cell,pose.feet())))continue;
                        if(reachableFrom(cell,pose)) {goals.add(pose.feet());if(++accepted>=40)break;}
                    }
                    nextCellPage=i+1;
                    // Every included cell gets its complete (<=40) pose set.
                    // Reserve room for the next cell instead of truncating it.
                    if(goals.size()>216)break;
                }
                workGoalCount=goals.size();omittedCells=layer.size()-nextCellPage;
                if(!goals.isEmpty()) {
                    // Restricted construction never excavates unrelated access
                    // or scaffolds outside the explicitly selected cells.
                    child=navigation.travel(List.copyOf(goals),Math.min(remaining,1200),false,false,override,lease);
                    phase="moving";state="traveling_to_work";return;
                }
                if(nextCellPage<layer.size()){cellPage=nextCellPage;state="finding_work_goals";return;}
            }
            inspection=inspect(cells,replace,override);
            if(Boolean.TRUE.equals(inspection.get("matches"))){finish("succeeded","all_selected_states_verified");return;}
            finish("failed","inaccessible_or_unsupported_cells");
        }
    }
    boolean reachableNow(Cell cell) {
        BlockPos p=cell.pos();if(WorkAccess.distance(p)>6)return false;
        Block current=world.getBlock(p.x(),p.y(),p.z());
        if(cell.clear()||!current.isAir(world,p.x(),p.y(),p.z())&&!current.isReplaceable(world,p.x(),p.y(),p.z()))return MiningJob.reachable(mc,world,p)!=null;
        return placementHeightAllowed(cell,mc.thePlayer.boundingBox.minY)&&ExactPlacementJob.reachable(cell,slot(cell));
    }
    boolean reachableFrom(Cell cell,WorkAccess.Pose pose) {
        BlockPos feet=pose.feet();
        BlockPos p=cell.pos();Block current=world.getBlock(p.x(),p.y(),p.z());
        if(cell.clear()||!current.isAir(world,p.x(),p.y(),p.z())&&!current.isReplaceable(world,p.x(),p.y(),p.z())) {
            if(p.equals(feet)||p.equals(new BlockPos(feet.x(),feet.y()-1,feet.z())))return false;
            // Mining retains its stricter stable full-support contract.
            if(!ForgeSnapshot.liveStandable(world,feet))return false;
            return MiningJob.reachable(mc,world,p,WorkAccess.eyeAt(pose))!=null;
        }
        return placementHeightAllowed(cell,pose.standingY())&&ExactPlacementJob.reachableFrom(cell,slot(cell),pose);
    }
    boolean placementHeightAllowed(Cell cell,double standingY) {
        // Retain BuilderProcess.searchForPlaceables / GoalAdjacent's height
        // rule: build at/below the feet, except one level up when capped above.
        // Mere ray reach lets a player build two-high walls from the ground and
        // strand the remaining roof. This rule climbs the growing structure
        // while its lower layers still provide access, without outside scaffolds.
        int feetY=(int)Math.floor(standingY+.001);
        return cell.pos().y()<=feetY || cell.pos().y()==feetY+1
            && !world.isAirBlock(cell.pos().x(),cell.pos().y()+1,cell.pos().z());
    }
    static String poseKey(Cell cell,BlockPos feet){return key(cell)+"@"+feet;}
    void reject(String reason,Map<String,Object> receipt){failedPoses.add(poseKey(target,WorkAccess.feet()));if(failures.size()<128)failures.add(Map.of("pos",point(target.pos()),"reason",reason,"detail",receipt));phase="choose";}
    static String key(Cell c){return c.pos().x()+","+c.pos().y()+","+c.pos().z();}
    @Override public Map<String,Object> status(){Map<String,Object> out=super.status();out.put("cells",cells==null?0:cells.size());out.put("placed",placed);out.put("removed",removed);out.put("target",target==null?null:point(target.pos()));out.put("inspection",inspection);out.put("blocked",failures);out.put("attemptedCells",attempts==null?0:attempts.size());out.put("workGoalCount",workGoalCount);out.put("deferredWorkCells",omittedCells);out.put("completionMeaning","fresh comparison of all selected block IDs and metadata; tile configuration requires separate adapter");return out;}
}
