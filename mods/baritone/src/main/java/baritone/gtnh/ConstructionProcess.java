// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
/* BuilderProcess construction semantics adapted to native Forge 1.7.10. LGPL-3.0-or-later. */
package baritone.gtnh;

import baritone.gtnh.pathing.*;
import static baritone.gtnh.pathing.WorkSpec.*;
import dev.modbench.api.Navigation;
import java.util.*;
import net.minecraft.block.Block;
import net.minecraft.item.*;

/** Live schematic builder. Strict DJ2 blueprints remain a separate process. */
final class ConstructionProcess extends BulkJob {
    final ConstructionSettings settings;
    final List<Cell> source;
    final Map<BlockPos,Cell> schematic=new LinkedHashMap<>();
    final LinkedHashSet<BlockPos> incorrect=new LinkedHashSet<>(),observedCompleted=new LinkedHashSet<>();
    final Set<String> rejected=new HashSet<>();
    final Map<String,Object> attempts;
    final List<Map<String,Object>> problems=new ArrayList<>();
    final List<Map<String,Object>> throwaways;
    List<Cell> cells;
    Cell target;
    int layer,repeat,scan,placed,removed,travelFailures;
    int minY,maxY;
    boolean sweepDone;
    String phase="scan";
    Map<String,Object> inspection;
    Map<BlockPos,Integer> placementGoals=new LinkedHashMap<>();
    Set<BlockPos> clearingGoals=new LinkedHashSet<>();

    ConstructionProcess(BaritoneNavigation navigation,WorkJournal journal,Map<String,Object> options) {
        super(navigation,journal,options);
        settings=new ConstructionSettings(child(params,"settings"));source=BuildingProcess.validatedCells(params);
        for(Cell c:source)if(!c.clear())BuildingProcess.block(c);
        throwaways=settings.values.containsKey("acceptableThrowawayItems")?WorkAccess.itemSelectors(settings.values.get("acceptableThrowawayItems")):List.of();
        attempts=new LinkedHashMap<>(child(journal.progress,"attempts"));journal.progress.put("attempts",attempts);
        repeat=integer(journal.progress,"repeat",0,0,100000);layer=integer(journal.progress,"layer",settings.integer("startAtLayer",0),0,256);
        placed=integer(journal.progress,"placed",0,0,Integer.MAX_VALUE);removed=integer(journal.progress,"removed",0,0,Integer.MAX_VALUE);
        installSchematic();
    }
    @Override String phase(){return phase;}
    void installSchematic() {
        schematic.clear();incorrect.clear();observedCompleted.clear();rejected.clear();scan=0;sweepDone=false;phase="scan";
        int[] size=new int[3];BlockPos origin=params.containsKey("origin")?pos(params.get("origin")):new BlockPos(0,0,0);
        if(params.containsKey("size")){var v=list(params.get("size"));for(int i=0;i<3;i++)size[i]=((Number)v.get(i)).intValue();}
        else {size[0]=source.stream().mapToInt(c->c.pos().x()).max().orElse(0)-source.stream().mapToInt(c->c.pos().x()).min().orElse(0)+1;size[1]=source.stream().mapToInt(c->c.pos().y()).max().orElse(0)-source.stream().mapToInt(c->c.pos().y()).min().orElse(0)+1;size[2]=source.stream().mapToInt(c->c.pos().z()).max().orElse(0)-source.stream().mapToInt(c->c.pos().z()).min().orElse(0)+1;}
        BlockPos offset=settings.repeat();long dx=(long)offset.x()*repeat+(settings.bool("schematicOrientationX",false)?size[0]:0),dy=(long)offset.y()*repeat+(settings.bool("schematicOrientationY",false)?size[1]:0),dz=(long)offset.z()*repeat+(settings.bool("schematicOrientationZ",false)?size[2]:0);
        String selectionKey="selection:"+(settings.bool("buildRepeatSneaky",false)?0:repeat);
        Set<String> selected=new HashSet<>();boolean frozen=journal.progress.containsKey(selectionKey);
        if(frozen)for(Object row:list(journal.progress.get(selectionKey)))selected.add((String)row);
        Map<String,Integer> tops=new HashMap<>();
        for(Cell c:source) {
            long x=c.pos().x()+dx,y=c.pos().y()+dy,z=c.pos().z()+dz;
            if(Math.abs(x)>30000000||Math.abs(z)>30000000||y<1||y>254)throw new IllegalArgumentException("repeated/oriented schematic outside world bounds");
            BlockPos p=new BlockPos((int)x,(int)y,(int)z);Cell moved=at(c,p);
            if(!c.replace().isEmpty()) {
                String key=BuildingProcess.key(c);
                if(frozen?!selected.contains(key):!WorkAccess.block(world,p,c.replace()))continue;
                selected.add(key);
            }
            schematic.put(p,moved);if(!c.clear())tops.merge(p.x()+","+p.z(),p.y(),Math::max);
        }
        if(!frozen)journal.progress.put(selectionKey,List.copyOf(selected));
        if(settings.bool("mapArtMode",false))schematic.entrySet().removeIf(e->e.getKey().y()<tops.getOrDefault(e.getKey().x()+","+e.getKey().z(),Integer.MAX_VALUE));
        cells=List.copyOf(schematic.values());
        minY=source.stream().mapToInt(c->c.pos().y()).min().orElse(origin.y())+(int)dy;
        maxY=params.containsKey("size")?origin.y()+(int)dy+size[1]-1:source.stream().mapToInt(c->c.pos().y()).max().orElse(minY)+(int)dy;
    }
    static Cell at(Cell c,BlockPos p){return new Cell(p,c.id(),c.meta(),c.clear(),c.item(),c.placement(),c.replace(),c.verify());}
    boolean inLayer(Cell c) {
        if(!settings.bool("buildInLayers",false))return true;
        int height=layer*settings.integer("layerHeight",1);
        return settings.bool("layerOrder",false)?c.pos().y()>maxY-height:c.pos().y()<minY+height;
    }
    int slot(Cell cell) {
        if(cell.clear())return -1;
        int slot=BuildingProcess.slot(cell);return slot>=9&&!settings.bool("allowInventory",true)?-1:slot;
    }
    Cell desired(Cell requested) {
        Object alternatives=settings.mappings("buildSubstitutes").get(requested.id());if(requested.clear()||alternatives==null)return requested;
        List<Cell> choices=new ArrayList<>();
        for(Object row:list(alternatives)) {
            Map<String,Object> state=row instanceof String s?Map.of("id",s):object(row);String id=string(state,"id","");
            Block block=(Block)Block.blockRegistry.getObject(id);if(block==null||!id.equals(Block.blockRegistry.getNameForObject(block)))throw new IllegalArgumentException("unknown substitute "+id);
            Cell c=new Cell(requested.pos(),id,integer(state,"meta",requested.meta(),0,15),block==net.minecraft.init.Blocks.air,state.containsKey("item")?child(state,"item"):Map.of(),state.containsKey("placement")?child(state,"placement"):requested.placement(),requested.replace(),state.containsKey("verify")?child(state,"verify"):requested.verify());
            choices.add(c);
            if(loaded(c.pos())&&!world.isAirBlock(c.pos().x(),c.pos().y(),c.pos().z())&&BuildingProcess.matches(c))return c;
        }
        for(Cell c:choices)if(c.clear()||slot(c)>=0)return c;
        return choices.get(0);
    }
    boolean loaded(BlockPos p){return ForgeSnapshot.loaded(world,p.x(),p.y(),p.z());}
    boolean correct(Cell requested) {
        BlockPos p=requested.pos();if(!loaded(p))return observedCompleted.contains(p);
        Cell c=desired(requested);Block actual=world.getBlock(p.x(),p.y(),p.z());boolean air=actual.isAir(world,p.x(),p.y(),p.z());String id=Block.blockRegistry.getNameForObject(actual);
        if(settings.bool("okIfWater",false)&&ForgeFluids.fluid(actual))return true;
        if(air&&settings.ids("okIfAir").contains(c.id()))return true;
        if(c.clear()&&settings.ids("buildIgnoreBlocks").contains(id))return true;
        if(!air&&settings.bool("buildIgnoreExisting",false)||settings.ids("buildSkipBlocks").contains(c.id()))return true;
        Object valid=settings.mappings("buildValidSubstitutes").get(c.id());if(valid!=null&&list(valid).contains(id))return BuildingProcess.verified(c);
        if(c.clear())return air;
        int mask=settings.metadataMask(c.id());return actual==BuildingProcess.block(c)&&(world.getBlockMetadata(p.x(),p.y(),p.z())&mask)==(c.meta()&mask)&&BuildingProcess.verified(c);
    }
    void observe(Cell c){if(!inLayer(c))return;if(correct(c)){incorrect.remove(c.pos());if(loaded(c.pos()))observedCompleted.add(c.pos());}else {observedCompleted.remove(c.pos());incorrect.add(c.pos());}}
    void nearby() {
        BlockPos feet=WorkAccess.feet();int r=settings.integer("builderTickScanRadius",5);
        for(int x=-r;x<=r;x++)for(int y=-r;y<=r;y++)for(int z=-r;z<=r;z++){Cell c=schematic.get(new BlockPos(feet.x()+x,feet.y()+y,feet.z()+z));if(c!=null)observe(c);}
        for(BlockPos p:List.copyOf(incorrect)){Cell c=schematic.get(p);if(c!=null)observe(c);}
    }
    @Override void step() {
        if(phase.equals("work")) {
            if(child!=null) {
                boolean ok=child.succeeded(),placement=child instanceof ExactPlacementJob;Map<String,Object> receipt=consumeChild();
                if(!ok){reject(target,"native_work_failed",receipt);if(placement&&Boolean.TRUE.equals(receipt.get("inputDelivered"))&&!settings.bool("repairPlaced",true)){pause("placement_not_verified_inspect_before_retry");return;}}
                else {if(placement)placed++;else removed++;rejected.clear();travelFailures=0;}
                journal.progress.put("placed",placed);journal.progress.put("removed",removed);journal.save(status());observe(schematic.getOrDefault(target.pos(),target));phase="choose";return;
            }
        }
        if(phase.equals("move")) {
            if(child!=null) {
                boolean ok=child.succeeded();var receipt=consumeChild();
                placed+=((Number)receipt.getOrDefault("blocksPlaced",0)).intValue();removed+=((Number)receipt.getOrDefault("blocksMined",0)).intValue();journal.progress.put("placed",placed);journal.progress.put("removed",removed);
                if(!ok){String why=String.valueOf(receipt.get("reason"));if(why.contains("damage")||why.contains("fire")||why.contains("death")||why.contains("hazard")){finish("failed",why);return;}travelFailures++;if(problems.size()<64)problems.add(Map.of("reason","work_route_failed","detail",receipt));}
            }
            phase="choose";
        }
        if(phase.equals("scan")) {
            int budget=4096,limit=settings.integer("incorrectSize",100);
            while(scan<cells.size()&&budget-->0&&incorrect.size()<limit)observe(cells.get(scan++));
            sweepDone=scan==cells.size();if(!sweepDone&&incorrect.isEmpty())return;phase="choose";
        }
        if(!phase.equals("choose"))return;
        nearby();
        if(incorrect.isEmpty()) {
            if(!sweepDone){phase="scan";return;}
            if(settings.bool("buildInLayers",false)&&layer*settings.integer("layerHeight",1)<maxY-minY+1){layer++;journal.progress.put("layer",layer);scan=0;sweepDone=false;phase="scan";return;}
            int count=settings.integer("buildRepeatCount",1);BlockPos offset=settings.repeat();
            if(!offset.equals(new BlockPos(0,0,0))&&(count==-1||repeat+1<count)) {repeat++;layer=settings.integer("startAtLayer",0);journal.progress.put("repeat",repeat);journal.progress.put("layer",layer);installSchematic();journal.save(status());return;}
            finish("succeeded","schematic_verified");return;
        }
        List<Cell> pending=incorrect.stream().map(schematic::get).filter(Objects::nonNull).map(this::desired).sorted(Comparator.comparingInt((Cell c)->c.clear()?-c.pos().y():c.pos().y()).thenComparingDouble(c->WorkAccess.distance(c.pos()))).toList();
        if(settings.bool("distanceTrim",true)&&pending.stream().anyMatch(c->distanceSquared(c.pos(),WorkAccess.feet())<200))pending=pending.stream().filter(c->distanceSquared(c.pos(),WorkAccess.feet())<200).toList();
        for(Cell c:pending)if(!rejected.contains(pose(c))&&reachable(c)) {
            target=c;String protection=WorkAccess.protection(c.pos(),override);if(protection!=null){reject(c,protection,Map.of());continue;}
            try {
                boolean occupied=occupied(c.pos());
                if(occupied||c.clear()) {
                    if(!bool(params,"replaceExisting",true)){reject(c,"replace_existing_disabled",Map.of());continue;}
                    if(!settings.bool("repairPlaced",true)&&attempts.containsKey(BuildingProcess.key(c))){pause("attempted_placement_changed");return;}
                    child=new MiningJob(mc,c.pos(),Math.min(remaining,6000),true,false,lease,override,true);
                } else child=placement(c,false);
                phase="work";state=occupied?"clearing":"placing";return;
            }catch(IllegalArgumentException error){reject(c,error.getMessage(),Map.of());}
        }
        List<Cell> available=pending.stream().filter(c->c.clear()||occupied(c.pos())||slot(c)>=0).toList();
        if(available.isEmpty()){inspection=materialReport(pending);pause("missing_materials");return;}
        for(Cell c:available)rejected.add(pose(c));
        LinkedHashSet<BlockPos> goals=new LinkedHashSet<>();placementGoals.clear();clearingGoals.clear();
        for(Cell c:available) {
            if(goals.size()>=192)break;
            for(var pose:WorkAccess.buildingApproaches(world,c.pos())) {
                if(goals.size()>=224)break;
                if(!pose.feet().equals(WorkAccess.feet())&&!rejected.contains(BuildingProcess.key(c)+"@"+pose.feet())&&reachableFrom(c,pose)){goals.add(pose.feet());goal(c,pose.feet());}
            }
            // GoalPlace/GoalAdjacent also admit currently unstandable nodes:
            // the construction graph can install their eventual support.
            if(allowPlace||allowBreak)for(int[] d:WorkAccess.SIDES) {
                BlockPos p=new BlockPos(c.pos().x()+d[0],c.pos().y()+Math.max(0,d[1]),c.pos().z()+d[2]);
                if(p.y()>254||p.equals(WorkAccess.feet())||rejected.contains(BuildingProcess.key(c)+"@"+p))continue;
                if(c.clear()&&p.x()==c.pos().x()&&p.z()==c.pos().z()&&!settings.bool("goalBreakFromAbove",false))continue;
                goals.add(p);goal(c,p);
            }
        }
        if(goals.isEmpty()||travelFailures>=3) {
            if(settings.bool("skipFailedLayers",false)&&settings.bool("buildInLayers",false)&&layer*settings.integer("layerHeight",1)<maxY-minY+1){layer++;journal.progress.put("layer",layer);incorrect.clear();scan=0;sweepDone=false;travelFailures=0;phase="scan";return;}
            inspection=materialReport(pending);pause("unreachable_or_unsupported_work");return;
        }
        child=navigation.travelConstruction(List.copyOf(goals),Math.min(remaining,2400),this);phase="move";state="traveling_to_work";
    }
    boolean occupied(BlockPos p){if(!loaded(p))return false;Block b=world.getBlock(p.x(),p.y(),p.z());return !b.isAir(world,p.x(),p.y(),p.z())&&!b.isReplaceable(world,p.x(),p.y(),p.z());}
    void goal(Cell c,BlockPos p){if(c.clear()||occupied(c.pos()))clearingGoals.add(p);else placementGoals.merge(p,c.pos().y(),Math::min);}
    Goal searchGoal(){return new ConstructionGoal(placementGoals,clearingGoals);}
    boolean heightAllowed(Cell c,double y){int feet=(int)Math.floor(y+.001);return c.pos().y()<=feet||c.pos().y()==feet+1&&!world.isAirBlock(c.pos().x(),c.pos().y()+1,c.pos().z());}
    boolean reachable(Cell c) {
        if(!loaded(c.pos())||WorkAccess.distance(c.pos())>6)return false;
        if(c.clear()||occupied(c.pos()))return (settings.bool("breakFromAbove",false)||c.pos().y()>=WorkAccess.feet().y())&&MiningJob.reachable(mc,world,c.pos())!=null;
        return heightAllowed(c,mc.thePlayer.boundingBox.minY)&&ExactPlacementJob.reachable(c,slot(c));
    }
    boolean reachableFrom(Cell c,WorkAccess.Pose pose) {
        if(c.clear()||occupied(c.pos()))return !c.pos().equals(pose.feet())&&!c.pos().equals(new BlockPos(pose.feet().x(),pose.feet().y()-1,pose.feet().z()))&&MiningJob.reachable(mc,world,c.pos(),WorkAccess.eyeAt(pose))!=null;
        return heightAllowed(c,pose.standingY())&&ExactPlacementJob.reachableFrom(c,slot(c),pose);
    }
    String pose(Cell c){return BuildingProcess.key(c)+"@"+WorkAccess.feet();}
    static double distanceSquared(BlockPos a,BlockPos b){return (double)(a.x()-b.x())*(a.x()-b.x())+(double)(a.y()-b.y())*(a.y()-b.y())+(double)(a.z()-b.z())*(a.z()-b.z());}
    void reject(Cell c,String why,Map<String,Object> receipt){rejected.add(pose(c));if(problems.size()<64)problems.add(Map.of("pos",point(c.pos()),"reason",why,"detail",receipt));}
    void pause(String why){finish("paused",why);}
    Map<String,Object> materialReport(List<Cell> pending){return BuildingProcess.inspect(pending,true,override);}
    Navigation.Job placement(Cell c,boolean pillar) {
        String key=BuildingProcess.key(c);int count=integer(attempts,key,0,0,Integer.MAX_VALUE);
        if(count>=8)throw new IllegalArgumentException("placement_attempt_limit_inspect_block_adapter");
        Runnable record=()->{journal.recordAttempt(key,count+1);attempts.put(key,count+1);journal.save(status());};
        return new ExactPlacementJob(c,slot(c),Math.min(remaining,200),lease,record,pillar);
    }
    Cell support(BlockPos p) {
        Cell requested=schematic.get(p);
        if(requested!=null&&inLayer(requested)) {Cell c=desired(requested);if(!c.clear()&&slot(c)>=0&&BuildingProcess.block(c).isNormalCube())return c;}
        if(settings.bool("restricted",false)&&(requested==null||!inLayer(requested)))return null;
        for(int i=0;i<(settings.bool("allowInventory",true)?36:9);i++) {
            ItemStack stack=mc.thePlayer.inventory.mainInventory[i];if(stack==null||!(stack.getItem() instanceof ItemBlock)||throwaways.stream().noneMatch(s->WorkAccess.item(stack,s)))continue;
            Block block=Block.getBlockFromItem(stack.getItem());if(block==null||!block.isNormalCube())continue;
            Map<String,Object> selector=new LinkedHashMap<>();selector.put("id",Item.itemRegistry.getNameForObject(stack.getItem()));selector.put("meta",stack.getItemDamage());if(stack.hasTagCompound())selector.put("nbt",stack.getTagCompound().toString());
            return new Cell(p,Block.blockRegistry.getNameForObject(block),dev.modbench.api.ControlRegistry.placement().initialMetadata(stack),false,selector,Map.of(),Map.of());
        }
        return null;
    }
    int placementBudget(){return Arrays.stream(mc.thePlayer.inventory.mainInventory).filter(Objects::nonNull).filter(s->s.getItem() instanceof ItemBlock).mapToInt(s->s.stackSize).sum();}
    WorkWorld.Construction costs(TerrainGrid terrain) {
        Map<BlockPos,Double> placements=new HashMap<>(),breaks=new HashMap<>();Set<BlockPos> fluids=new HashSet<>();
        boolean restricted=settings.bool("restricted",false);Set<BlockPos> permitted=new HashSet<>();
        double outside=support(new BlockPos(0,0,0))==null?Double.POSITIVE_INFINITY:30;
        // Capture only schematic exceptions. The search thread never calls a
        // Minecraft registry/world/inventory callback or scans the full volume.
        for(Cell desired:cells) {
            BlockPos p=desired.pos();if(!terrain.isLoaded(p.x(),p.y(),p.z())||!inLayer(desired))continue;permitted.add(p);
            if(desired!=null&&correct(desired))breaks.put(p,settings.number("breakCorrectBlockPenaltyMultiplier",10));
            if(ForgeFluids.fluid(world.getBlock(p.x(),p.y(),p.z()))&&world.getBlock(p.x(),p.y(),p.z()).isReplaceable(world,p.x(),p.y(),p.z()))fluids.add(p);
            if(terrain.clear(p.x(),p.y(),p.z())||fluids.contains(p)) {Cell support=support(p);double price=support==null?Double.POSITIVE_INFINITY:desired==null?30:desired.clear()?60:support.id().equals(desired(desired).id())&&support.meta()==desired(desired).meta()?0:90;placements.put(p,price);}
        }
        var frozenPlace=Map.copyOf(placements);var frozenBreak=Map.copyOf(breaks);
        return new WorkWorld.Construction(){public double placementCost(BlockPos p){return restricted&&!permitted.contains(p)?Double.POSITIVE_INFINITY:frozenPlace.getOrDefault(p,outside);}public double breakMultiplier(BlockPos p){return restricted&&!permitted.contains(p)?Double.POSITIVE_INFINITY:frozenBreak.getOrDefault(p,1.0);}public boolean replaceFluid(BlockPos p){return fluids.contains(p);}};
    }
    @Override public Map<String,Object> status() {
        var out=super.status();out.put("mode","builder");out.put("phase",phase);out.put("settings",settings==null?Map.of():settings.values);out.put("cells",cells==null?0:cells.size());out.put("incorrect",incorrect.size());out.put("scanCursor",scan);out.put("layer",layer);out.put("repeat",repeat);out.put("placed",placed);out.put("removed",removed);out.put("target",target==null?null:point(target.pos()));out.put("inspection",inspection);out.put("blocked",problems);out.put("completionMeaning","schematic predicate over observed block IDs and metadata; configured ignores/substitutions apply; completed unloaded cells remembered during this process");return out;
    }
}
