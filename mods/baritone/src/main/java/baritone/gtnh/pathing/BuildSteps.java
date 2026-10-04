// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh.pathing;

import baritone.compat.BlockPos;
import java.util.*;
import static baritone.gtnh.pathing.WorkSpec.*;

/**
 * The stepped build order. Every cell that must hold a block has a step: its stage, then its height. The source
 * builder is shown only the cells of steps up to the current one, and nothing may be placed in a cell it is not
 * shown, so a plan rises one layer at a time and a later stage waits for the earlier ones. Cells that must be
 * empty have no step: emptying is never delayed. A stage's click cells are one step after its plain layers, and
 * its uses one step after that: the click executor works those, and the source builder is never shown them.
 * The order is the plan's own (what it names and how high each cell is); nothing here looks at what the plan is
 * a plan of.
 */
public final class BuildSteps {
    public static final int STAGES=256;
    /** What a step is made of: the plain cells of one height, a stage's click cells, a stage's uses. */
    public static final int CELLS=0,CLICKS=1,USES=2;
    private final int[] keys,sizes,index;
    private final Map<BlockPos,Integer> at;
    public BuildSteps(List<Cell> cells){this(cells,List.of());}
    /** cells in the order the job walks them each tick; uses in the order given. */
    public BuildSteps(List<Cell> cells,List<StepPlan.Step> uses) {
        index=new int[cells.size()];Arrays.fill(index,-1);
        keys=java.util.stream.IntStream.concat(cells.stream().filter(c->!c.clear()).mapToInt(BuildSteps::key),uses.stream().mapToInt(u->u.stage()<<10|USES<<8)).distinct().sorted().toArray();sizes=new int[keys.length];
        Map<BlockPos,Integer> steps=new HashMap<>();
        for(int i=0;i<index.length;i++){
            Cell c=cells.get(i);if(c.clear())continue;
            index[i]=Arrays.binarySearch(keys,key(c));sizes[index[i]]++;steps.put(c.pos(),index[i]);
        }
        for(var u:uses)sizes[uses(u.stage())]++;
        at=steps;
    }
    private static int key(Cell c){return c.stage()<<10|(c.click()!=null?CLICKS<<8:c.pos().getY());}
    public int count(){return keys.length;}
    public int stage(int step){return keys[step]>>10;}
    public int kind(int step){return keys[step]>>8&3;}
    public int y(int step){return keys[step]&255;}
    /** The step of the i-th cell given to the constructor, or -1 for one that is never delayed. */
    public int index(int i){return index[i];}
    /** The step of a stage's uses. */
    public int uses(int stage){return Arrays.binarySearch(keys,stage<<10|USES<<8);}
    public boolean visible(BlockPos p,int current){Integer step=at.get(p);return step==null||step<=current;}
    /**
     * The current step: the first one, at or after `from`, with a cell left. A fresh or resumed job starts from 0,
     * so the step is read from the world, never stored. Within a job it only goes forward: a cell of an earlier
     * step that breaks later stays shown and is repaired, without hiding what was built above it meanwhile.
     */
    public static int current(int from,int[] left){int step=from;while(step<left.length&&left[step]==0)step++;return step;}
    /** What the source builder is shown at `current`: the plain cells of the steps so far. */
    public <T> Map<BlockPos,T> schematic(Map<BlockPos,T> desired,int current) {
        if(current>=keys.length&&Arrays.stream(keys).allMatch(k->(k>>8&3)==CELLS))return desired;
        Map<BlockPos,T> shown=new HashMap<>(desired);shown.keySet().removeIf(p->{Integer step=at.get(p);return step!=null&&(step>current||kind(step)!=CELLS);});return Map.copyOf(shown);
    }
    private Map<String,Object> name(int step) {
        Map<String,Object> out=new LinkedHashMap<>();out.put("stage",stage(step));
        if(kind(step)==CELLS)out.put("y",y(step));else out.put("phase",kind(step)==CLICKS?"clicks":"uses");
        return out;
    }
    /** {stage, y} of the step `p` belongs to ({stage, phase} for clicks and uses); of step `current` for no cell, or one that has no step. */
    public Map<String,Object> where(BlockPos p,int current) {
        if(keys.length==0)return Map.of();
        Integer own=p==null?null:at.get(p);return name(Math.min(own!=null?own:current,keys.length-1));
    }
    /** Preview: every step in order with the number of cells (or uses) it holds. */
    public List<Map<String,Object>> list() {
        List<Map<String,Object>> out=new ArrayList<>();
        for(int i=0;i<keys.length;i++){var row=name(i);row.put(kind(i)==USES?"uses":"cells",sizes[i]);out.add(row);}
        return out;
    }
    /**
     * Receipt: the step the job is on, counted from 1 (a finished plan stands on its last), how many shown cells
     * are still wrong and the first of them. left and first are per step, as counted this tick.
     */
    public Map<String,Object> receipt(int current,int[] left,BlockPos[] first) {
        if(keys.length==0||left.length!=keys.length)return Map.of();
        int on=Math.min(current,keys.length-1),wrong=0;BlockPos next=null;
        for(int i=0;i<=on;i++){wrong+=left[i];if(next==null&&left[i]>0)next=first[i];}
        var out=name(on);
        out.put("index",on+1);out.put("of",keys.length);out.put("left",wrong);
        if(next!=null)out.put("first",List.of(next.getX(),next.getY(),next.getZ()));
        return out;
    }
}
