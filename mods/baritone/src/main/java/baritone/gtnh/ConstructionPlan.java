// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
/* BuilderProcess construction semantics adapted to native Forge 1.7.10. LGPL-3.0-or-later. */
package baritone.gtnh;

import baritone.compat.Registry;
import baritone.compat.BlockPos;
import baritone.gtnh.pathing.*;
import static baritone.gtnh.pathing.WorkSpec.*;
import java.util.*;
import net.minecraft.block.Block;
import net.minecraft.item.*;
import net.minecraft.world.World;

/**
 * Frozen construction plan: validated cells, journaled selection, repeat and
 * orientation, substitutes, the live cell predicate and the preview diff. It
 * schedules nothing; ReferenceConstructionProcess runs it through BuilderProcess.
 * Mode blueprint is the strict profile of the same plan: restricted to its
 * cells, preflighted, no replacement unless asked, two attempts per cell and
 * no destructive repair of an attempted cell.
 */
final class ConstructionPlan {
    final Map<String,Object> params,progress;
    final World world;
    final boolean strict;
    final ConstructionSettings settings;
    final List<Cell> source;
    final Map<BlockPos,Cell> schematic=new LinkedHashMap<>();
    final Map<String,Object> attempts;
    final List<Map<String,Object>> throwaways;
    List<Cell> cells;
    int layer,repeat,minY,maxY;

    /** progress is the journal's map for a job, or a scratch map for a preview. */
    ConstructionPlan(Map<String,Object> params,Map<String,Object> progress,World world) {
        this.params=params;this.progress=progress;this.world=world;strict=!"builder".equals(params.get("mode"));
        settings=new ConstructionSettings(child(params,"settings"));source=validatedCells(params);
        for(Cell c:source)if(!c.clear())block(c);
        throwaways=settings.values.containsKey("acceptableThrowawayItems")?WorkAccess.itemSelectors(settings.values.get("acceptableThrowawayItems")):List.of();
        attempts=new LinkedHashMap<>(child(progress,"attempts"));progress.put("attempts",attempts);
        repeat=integer(progress,"repeat",0,0,100000);layer=integer(progress,"layer",settings.integer("startAtLayer",0),0,256);
        installSchematic();
    }
    String mode(){return strict?"blueprint":"builder";}
    boolean restricted(){return strict||settings.bool("restricted",false);}
    boolean repairPlaced(){return !strict&&settings.bool("repairPlaced",true);}
    boolean replace(){return bool(params,"replaceExisting",!strict);}
    int attemptLimit(){return strict?2:8;}
    void installSchematic() {
        schematic.clear();
        int[] size=new int[3];BlockPos origin=params.containsKey("origin")?pos(params.get("origin")):new BlockPos(0,0,0);
        if(params.containsKey("size")){var v=list(params.get("size"));for(int i=0;i<3;i++)size[i]=((Number)v.get(i)).intValue();}
        else {size[0]=source.stream().mapToInt(c->c.pos().getX()).max().orElse(0)-source.stream().mapToInt(c->c.pos().getX()).min().orElse(0)+1;size[1]=source.stream().mapToInt(c->c.pos().getY()).max().orElse(0)-source.stream().mapToInt(c->c.pos().getY()).min().orElse(0)+1;size[2]=source.stream().mapToInt(c->c.pos().getZ()).max().orElse(0)-source.stream().mapToInt(c->c.pos().getZ()).min().orElse(0)+1;}
        BlockPos offset=settings.repeat();long dx=(long)offset.getX()*repeat+(settings.bool("schematicOrientationX",false)?size[0]:0),dy=(long)offset.getY()*repeat+(settings.bool("schematicOrientationY",false)?size[1]:0),dz=(long)offset.getZ()*repeat+(settings.bool("schematicOrientationZ",false)?size[2]:0);
        String selectionKey="selection:"+(settings.bool("buildRepeatSneaky",false)?0:repeat);
        Set<String> selected=new HashSet<>();boolean frozen=progress.containsKey(selectionKey);
        if(frozen)for(Object row:list(progress.get(selectionKey)))selected.add((String)row);
        Map<String,Integer> tops=new HashMap<>();
        for(Cell c:source) {
            long x=c.pos().getX()+dx,y=c.pos().getY()+dy,z=c.pos().getZ()+dz;
            if(Math.abs(x)>30000000||Math.abs(z)>30000000||y<1||y>254)throw new IllegalArgumentException("repeated/oriented schematic outside world bounds");
            BlockPos p=new BlockPos((int)x,(int)y,(int)z);Cell moved=at(c,p);
            if(!c.replace().isEmpty()) {
                String key=key(c);
                if(frozen?!selected.contains(key):!WorkAccess.block(world,p,c.replace()))continue;
                selected.add(key);
            }
            schematic.put(p,moved);if(!c.clear())tops.merge(p.getX()+","+p.getZ(),p.getY(),Math::max);
        }
        if(!frozen)progress.put(selectionKey,List.copyOf(selected));
        if(settings.bool("mapArtMode",false))schematic.entrySet().removeIf(e->e.getKey().getY()<tops.getOrDefault(e.getKey().getX()+","+e.getKey().getZ(),Integer.MAX_VALUE));
        cells=List.copyOf(schematic.values());
        minY=source.stream().mapToInt(c->c.pos().getY()).min().orElse(origin.getY())+(int)dy;
        maxY=params.containsKey("size")?origin.getY()+(int)dy+size[1]-1:source.stream().mapToInt(c->c.pos().getY()).max().orElse(minY)+(int)dy;
    }
    static Cell at(Cell c,BlockPos p){return new Cell(p,c.id(),c.meta(),c.clear(),c.item(),c.placement(),c.replace(),c.verify());}
    boolean inLayer(Cell c) {
        if(!settings.bool("buildInLayers",false))return true;
        int height=layer*settings.integer("layerHeight",1);
        return settings.bool("layerOrder",false)?c.pos().getY()>maxY-height:c.pos().getY()<minY+height;
    }
    int slot(Cell cell) {
        if(cell.clear())return -1;
        int slot=inventorySlot(cell);return slot>=9&&!settings.bool("allowInventory",true)?-1:slot;
    }
    Cell desired(Cell requested) {
        Object alternatives=settings.mappings("buildSubstitutes").get(requested.id());if(requested.clear()||alternatives==null)return requested;
        List<Cell> choices=new ArrayList<>();
        for(Object row:list(alternatives)) {
            Map<String,Object> state=row instanceof String s?Map.of("id",s):object(row);String id=string(state,"id","");
            Block block=Registry.block(id);
            Cell c=new Cell(requested.pos(),id,integer(state,"meta",requested.meta(),0,15),block==net.minecraft.init.Blocks.air,state.containsKey("item")?child(state,"item"):Map.of(),state.containsKey("placement")?child(state,"placement"):requested.placement(),requested.replace(),state.containsKey("verify")?child(state,"verify"):requested.verify());
            choices.add(c);
            if(loaded(c.pos())&&!world.isAirBlock(c.pos().getX(),c.pos().getY(),c.pos().getZ())&&matches(c))return c;
        }
        for(Cell c:choices)if(c.clear()||slot(c)>=0)return c;
        return choices.get(0);
    }
    boolean loaded(BlockPos p){return ForgeSnapshot.loaded(world,p.getX(),p.getY(),p.getZ());}
    boolean correct(Cell requested) {
        BlockPos p=requested.pos();if(!loaded(p))return false;
        Cell c=desired(requested);Block actual=world.getBlock(p.getX(),p.getY(),p.getZ());boolean air=actual.isAir(world,p.getX(),p.getY(),p.getZ());String id=Registry.name(actual);
        if(settings.bool("okIfWater",false)&&ForgeFluids.fluid(actual))return true;
        if(air&&settings.ids("okIfAir").contains(c.id()))return true;
        if(c.clear()&&settings.ids("buildIgnoreBlocks").contains(id))return true;
        if(!air&&settings.bool("buildIgnoreExisting",false)||settings.ids("buildSkipBlocks").contains(c.id()))return true;
        Object valid=settings.mappings("buildValidSubstitutes").get(c.id());if(valid!=null&&list(valid).contains(id))return verified(c);
        if(c.clear())return air;
        int mask=settings.metadataMask(c.id());return actual==block(c)&&(world.getBlockMetadata(p.getX(),p.getY(),p.getZ())&mask)==(c.meta()&mask)&&verified(c);
    }
    boolean occupied(BlockPos p){if(!loaded(p))return false;Block b=world.getBlock(p.getX(),p.getY(),p.getZ());return !b.isAir(world,p.getX(),p.getY(),p.getZ())&&!b.isReplaceable(world,p.getX(),p.getY(),p.getZ());}
    static List<Cell> validatedCells(Map<String,Object> params) {
        var cells=WorkSpec.cells(params);
        for(Cell cell:cells) {
            if(!cell.replace().isEmpty())WorkAccess.validateBlockSelector(cell.replace());
            if(!cell.item().isEmpty())WorkAccess.validateItemSelector(cell.item());
            if(cell.verify().containsKey("pickedItem"))WorkAccess.validateItemSelector(child(cell.verify(),"pickedItem"));
        }
        return cells;
    }
    static Block block(Cell c){return Registry.block(c.id());}
    static boolean matches(Cell c) {var world=WorkAccess.MC.theWorld;return ForgeSnapshot.loaded(world,c.pos().getX(),c.pos().getY(),c.pos().getZ())&&(c.clear()?world.isAirBlock(c.pos().getX(),c.pos().getY(),c.pos().getZ()):world.getBlock(c.pos().getX(),c.pos().getY(),c.pos().getZ())==block(c)&&world.getBlockMetadata(c.pos().getX(),c.pos().getY(),c.pos().getZ())==c.meta())&&verified(c);}
    static boolean verified(Cell c){return !c.verify().containsKey("pickedItem")||WorkAccess.item(WorkAccess.picked(WorkAccess.MC.theWorld,c.pos()),child(c.verify(),"pickedItem"));}
    static Map<String,Object> material(Cell c) {
        if(!c.item().isEmpty()){WorkAccess.validateItemSelector(c.item());return c.item();}
        Block block=block(c);Item item=Item.getItemFromBlock(block);
        if(item==null)throw new IllegalArgumentException("no native item mapping for "+c.id()+"; provide item selector and placement adapter");
        return Map.of("id",Registry.name(item),"meta",block.damageDropped(c.meta()));
    }
    static int inventorySlot(Cell c) {
        Map<String,Object> selector=material(c);int held=WorkAccess.MC.thePlayer.inventory.currentItem;
        if(WorkAccess.item(WorkAccess.MC.thePlayer.getHeldItem(),selector))return held;
        for(int i=0;i<36;i++)if(WorkAccess.item(WorkAccess.MC.thePlayer.inventory.mainInventory[i],selector))return i;return -1;
    }
    static Map<String,Object> inspect(List<Cell> cells,boolean replace,boolean override) {
        var mc=WorkAccess.MC;Map<Map<String,Object>,Integer> required=new LinkedHashMap<>();List<Map<String,Object>> differences=new ArrayList<>();
        int correct=0,unloaded=0,conflicts=0,protectedCount=0,unsupported=0;
        for(Cell c:cells) {
            String reason="different";if(matches(c)){correct++;continue;}
            if(!ForgeSnapshot.loaded(mc.theWorld,c.pos().getX(),c.pos().getY(),c.pos().getZ())){unloaded++;reason="unloaded";}
            else {
                Block actual=mc.theWorld.getBlock(c.pos().getX(),c.pos().getY(),c.pos().getZ());
                if(!replace&&!actual.isAir(mc.theWorld,c.pos().getX(),c.pos().getY(),c.pos().getZ())&&!actual.isReplaceable(mc.theWorld,c.pos().getX(),c.pos().getY(),c.pos().getZ())){conflicts++;reason="occupied";}
                String protection=WorkAccess.protection(c.pos(),override);if(protection!=null){protectedCount++;reason=protection;}
            }
            if(!c.clear())try{required.merge(material(c),1,Integer::sum);}catch(IllegalArgumentException e){unsupported++;reason=e.getMessage();}
            if(differences.size()<64) {
                Map<String,Object> expected=new LinkedHashMap<>(c.clear()?Map.of("clear",true):Map.of("id",c.id(),"meta",c.meta()));Map<String,Object> actual=WorkAccess.observed(mc.theWorld,c.pos());
                if(!c.verify().isEmpty()){expected.put("verify",c.verify());if(ForgeSnapshot.loaded(mc.theWorld,c.pos().getX(),c.pos().getY(),c.pos().getZ()))actual.put("pickedItem",InventorySelection.describe(WorkAccess.picked(mc.theWorld,c.pos())));}
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
    static String key(Cell c){return c.pos().getX()+","+c.pos().getY()+","+c.pos().getZ();}
    /** Fresh diff of the selected cells: blueprint compares every cell, builder only what its schematic predicate rejects. */
    Map<String,Object> preview(boolean override) {
        if(strict)return inspect(cells,replace(),override);
        var pending=cells.stream().filter(c->!correct(c)).map(this::desired).toList();var out=inspect(pending,true,override);
        out.put("mode","builder");out.put("selected",cells.size());out.put("acceptedBySchematic",cells.size()-pending.size());out.put("settings",settings.values);return out;
    }
}
