// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
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
 * Frozen construction plan: validated cells, the journaled replace selection, the build order, the live cell
 * predicate and the preview diff. It schedules nothing; ReferenceConstructionProcess runs it through BuilderProcess.
 */
final class ConstructionPlan {
    /** Clicks the game may take into one cell, without the block being seen there, before the job stops on that cell. */
    static final int ATTEMPTS=8;
    /** How many rows a preview or a receipt lists of anything it also counts. */
    static final int FIRST=8;
    final Map<String,Object> params;
    final World world;
    /** The plan by position, in the order of `cells`. Never changed once made: path searches read it from their own threads. */
    final Map<BlockPos,Cell> schematic=new LinkedHashMap<>();
    final List<Cell> cells;
    /** The build order of `cells`, index for index. */
    final BuildSteps steps;
    private final Map<String,Block> blocks=new HashMap<>();

    /** progress is the journal's map for a job, or a scratch map for a preview. */
    ConstructionPlan(Map<String,Object> params,Map<String,Object> progress,World world) {
        this.params=params;this.world=world;
        List<Cell> source=validatedCells(params);
        for(Cell c:source)if(!c.clear())blocks.put(c.id(),block(c));
        // A replace selection is decided once, against the world the job first saw, and journaled: a resume builds the same cells.
        Set<String> selected=new HashSet<>();boolean frozen=progress.containsKey("selection");
        if(frozen)for(Object row:list(progress.get("selection")))selected.add((String)row);
        for(Cell c:source) {
            if(!c.replace().isEmpty()) {
                String key=key(c);
                if(frozen?!selected.contains(key):!WorkAccess.block(world,c.pos(),c.replace()))continue;
                selected.add(key);
            }
            schematic.put(c.pos(),c);
        }
        if(!frozen)progress.put("selection",List.copyOf(selected));
        cells=List.copyOf(schematic.values());steps=new BuildSteps(cells);
    }
    boolean replace(){return bool(params,"replaceExisting",false);}
    int slot(Cell cell){return cell.clear()?-1:inventorySlot(cell);}
    boolean loaded(BlockPos p){return ForgeSnapshot.loaded(world,p.getX(),p.getY(),p.getZ());}
    /** The world holds what the plan asks at this cell. */
    boolean correct(Cell c) {
        BlockPos p=c.pos();if(!loaded(p))return false;
        Block actual=world.getBlock(p.getX(),p.getY(),p.getZ());
        if(c.clear())return actual.isAir(world,p.getX(),p.getY(),p.getZ());
        return actual==blocks.get(c.id())&&(c.anyMeta()||world.getBlockMetadata(p.getX(),p.getY(),p.getZ())==c.meta())&&verified(c);
    }
    /** A block stands in the cell that a placement would not replace (LegacyPlacement.empty is the one meaning of empty). */
    boolean occupied(BlockPos p){return loaded(p)&&!baritone.compat.LegacyPlacement.empty(world,p.getX(),p.getY(),p.getZ());}
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
    static boolean verified(Cell c){return !c.verify().containsKey("pickedItem")||WorkAccess.item(WorkAccess.picked(WorkAccess.MC.theWorld,c.pos()),child(c.verify(),"pickedItem"));}
    static Map<String,Object> material(Cell c) {
        if(!c.item().isEmpty()){WorkAccess.validateItemSelector(c.item());return c.item();}
        Block block=block(c);Item item=Item.getItemFromBlock(block);
        if(item==null)throw new IllegalArgumentException("no native item mapping for "+c.id()+"; name the item that places it (cell item)");
        return Map.of("id",Registry.name(item),"meta",block.damageDropped(c.meta()));
    }
    static int inventorySlot(Cell c) {
        Map<String,Object> selector=material(c);int held=WorkAccess.MC.thePlayer.inventory.currentItem;
        if(WorkAccess.item(WorkAccess.MC.thePlayer.getHeldItem(),selector))return held;
        for(int i=0;i<36;i++)if(WorkAccess.item(WorkAccess.MC.thePlayer.inventory.mainInventory[i],selector))return i;return -1;
    }
    /**
     * What these placements need against what is carried, one row per material {selector, needed, allocated, missing}.
     * One shared count of the inventory: overlapping selectors must not each claim the same stack.
     */
    static List<Map<String,Object>> materials(Map<Map<String,Object>,Integer> required) {
        var inventory=WorkAccess.MC.thePlayer.inventory.mainInventory;
        int[] available=new int[36];for(int i=0;i<36;i++)if(inventory[i]!=null)available[i]=inventory[i].stackSize;
        List<Map<String,Object>> rows=new ArrayList<>();
        var ordered=new ArrayList<>(required.entrySet());ordered.sort(Comparator.comparingInt((Map.Entry<Map<String,Object>,Integer> e)->-e.getKey().size()));
        for(var e:ordered) {
            int need=e.getValue(),supplied=0;
            for(int i=0;i<36&&supplied<need;i++)if(WorkAccess.item(inventory[i],e.getKey())){int take=Math.min(need-supplied,available[i]);available[i]-=take;supplied+=take;}
            rows.add(Map.of("selector",e.getKey(),"needed",need,"allocated",supplied,"missing",need-supplied));
        }
        return rows;
    }
    static String key(Cell c){return c.pos().getX()+","+c.pos().getY()+","+c.pos().getZ();}
    /**
     * Fresh diff of the plan against the world, judged as the job will judge it: counts, the materials, the first
     * FIRST cells that differ and the steps in order. Lists are short by design: the counts say how much there is.
     */
    Map<String,Object> preview(boolean override) {
        Map<Map<String,Object>,Integer> required=new LinkedHashMap<>();List<Map<String,Object>> differences=new ArrayList<>();
        int correct=0,unloaded=0,conflicts=0,protectedCount=0,unsupported=0;boolean replace=replace();
        for(Cell c:cells) {
            String reason="different";if(correct(c)){correct++;continue;}
            BlockPos p=c.pos();
            if(!loaded(p)){unloaded++;reason="unloaded";}
            else {
                // A cell to be emptied is never a conflict: clearing it is what was asked.
                if(!replace&&!c.clear()&&occupied(p)){conflicts++;reason="occupied";}
                String protection=WorkAccess.protection(p,override);if(protection!=null){protectedCount++;reason=protection;}
            }
            if(!c.clear())try{required.merge(material(c),1,Integer::sum);}catch(IllegalArgumentException e){unsupported++;reason=e.getMessage();}
            if(differences.size()<FIRST) {
                Map<String,Object> expected=new LinkedHashMap<>(),actual=new LinkedHashMap<>();
                if(c.clear())expected.put("clear",true);else{expected.put("id",c.id());if(!c.anyMeta())expected.put("meta",c.meta());}
                actual.put("loaded",loaded(p));
                if(loaded(p)){actual.put("id",Registry.name(world.getBlock(p.getX(),p.getY(),p.getZ())));actual.put("meta",world.getBlockMetadata(p.getX(),p.getY(),p.getZ()));}
                if(!c.verify().isEmpty()){expected.put("verify",c.verify());if(loaded(p))actual.put("pickedItem",InventorySelection.describe(WorkAccess.picked(world,p)));}
                differences.add(Map.of("pos",point(p),"expected",expected,"actual",actual,"reason",reason));
            }
        }
        var rows=materials(required);var all=steps.list();
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("total",cells.size());out.put("correct",correct);out.put("mismatched",cells.size()-correct);out.put("matches",correct==cells.size());
        out.put("unloaded",unloaded);out.put("conflicts",conflicts);out.put("protected",protectedCount);out.put("unsupported",unsupported);
        out.put("missingItems",rows.stream().mapToInt(r->(Integer)r.get("missing")).sum());
        out.put("materialCount",rows.size());out.put("materials",rows.subList(0,Math.min(4*FIRST,rows.size())));
        out.put("differences",differences);out.put("differencesTruncated",cells.size()-correct>differences.size());
        out.put("stepCount",all.size());out.put("steps",all.subList(0,Math.min(4*FIRST,all.size())));
        return out;
    }
}
