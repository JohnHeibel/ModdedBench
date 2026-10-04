// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh.pathing;

import baritone.compat.BlockPos;
import java.util.*;
import java.util.function.Predicate;
import static baritone.gtnh.pathing.WorkSpec.*;

/**
 * The stepped build order. Every cell that must hold a block has a step: its stage, then its height. The source
 * builder is shown only the cells of steps up to the current one, and nothing may be placed in a cell it is not
 * shown, so a plan rises one layer at a time and a later stage waits for the earlier ones. Cells that must be
 * empty have no step: emptying is never delayed. The order is the plan's own (what it names and how high each
 * cell is); nothing here looks at what the plan is a plan of.
 */
public final class BuildSteps {
    public static final int STAGES=256;
    private final int[] keys,sizes,index;
    private final Map<BlockPos,Integer> at;
    /** cells in the order the job walks them each tick. */
    public BuildSteps(List<Cell> cells) {
        index=new int[cells.size()];Arrays.fill(index,-1);
        keys=cells.stream().filter(c->!c.clear()).mapToInt(BuildSteps::key).distinct().sorted().toArray();sizes=new int[keys.length];
        Map<BlockPos,Integer> steps=new HashMap<>();
        for(int i=0;i<index.length;i++){
            Cell c=cells.get(i);if(c.clear())continue;
            index[i]=Arrays.binarySearch(keys,key(c));sizes[index[i]]++;steps.put(c.pos(),index[i]);
        }
        at=steps;
    }
    private static int key(Cell c){return c.stage()<<8|c.pos().getY();}
    public int count(){return keys.length;}
    public int stage(int step){return keys[step]>>8;}
    public int y(int step){return keys[step]&255;}
    /** The step of the i-th cell given to the constructor, or -1 for one that is never delayed. */
    public int index(int i){return index[i];}
    public boolean visible(BlockPos p,int current){Integer step=at.get(p);return step==null||step<=current;}
    /**
     * The current step: the first one, at or after `from`, with a cell left. A fresh or resumed job starts from 0,
     * so the step is read from the world, never stored. Within a job it only goes forward: a cell of an earlier
     * step that breaks later stays shown and is repaired, without hiding what was built above it meanwhile.
     */
    public static int current(int from,int[] left){int step=from;while(step<left.length&&left[step]==0)step++;return step;}
    /** What the source builder is shown at `current`. */
    public <T> Map<BlockPos,T> schematic(Map<BlockPos,T> desired,int current) {
        if(current>=keys.length)return desired;
        Map<BlockPos,T> shown=new HashMap<>(desired);shown.keySet().removeIf(p->!visible(p,current));return Map.copyOf(shown);
    }
    /** {stage, y} of the step `p` belongs to; of step `current` for no cell, or one that has no step. */
    public Map<String,Object> where(BlockPos p,int current) {
        if(keys.length==0)return Map.of();
        Integer own=p==null?null:at.get(p);int step=Math.min(own!=null?own:current,keys.length-1);
        return Map.of("stage",stage(step),"y",y(step));
    }
    /** held: cells kept from the source builder for now. first: the deeper cells they wait for. */
    public record Held(Set<BlockPos> held,Set<BlockPos> first){public static final Held NONE=new Held(Set.of(),Set.of());}
    private static final int[][] SIDES={{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
    private static BlockPos beside(BlockPos p,int[] side){return new BlockPos(p.getX()+side[0],p.getY()+side[1],p.getZ()+side[2]);}
    /**
     * Which of the cells still to be filled wait for now. A cell is reached through an open side; one whose only open
     * sides are other cells to be filled lies behind them, and filled after them it can no longer be reached (the corner
     * of a lining under a ceiling, the end of a blind passage). So each pending cell has a depth, the number of pending
     * cells between it and a side that stays open, and a cell waits while a pending neighbour lies deeper. Cells with open
     * sides everywhere hold nothing back; cells with no way out at all have no depth, and neither wait nor are waited for.
     * open: whether a cell that is not pending is empty.
     */
    public static Held held(Set<BlockPos> pending,Predicate<BlockPos> open) {
        Map<BlockPos,Integer> depth=new HashMap<>();ArrayDeque<BlockPos> queue=new ArrayDeque<>();
        for(BlockPos p:pending)for(int[] side:SIDES){BlockPos n=beside(p,side);if(!pending.contains(n)&&open.test(n)){depth.put(p,0);queue.add(p);break;}}
        if(depth.size()==pending.size())return Held.NONE;
        while(!queue.isEmpty()){
            BlockPos p=queue.poll();int next=depth.get(p)+1;
            for(int[] side:SIDES){BlockPos n=beside(p,side);if(pending.contains(n)&&depth.putIfAbsent(n,next)==null)queue.add(n);}
        }
        Set<BlockPos> held=new HashSet<>(),first=new HashSet<>();
        for(var e:depth.entrySet())for(int[] side:SIDES){BlockPos n=beside(e.getKey(),side);Integer d=depth.get(n);if(d!=null&&d>e.getValue()){held.add(e.getKey());first.add(n);}}
        return held.isEmpty()?Held.NONE:new Held(Set.copyOf(held),Set.copyOf(first));
    }
    /** Preview: every step in order with the number of cells it holds. */
    public List<Map<String,Object>> list() {
        List<Map<String,Object>> out=new ArrayList<>();
        for(int i=0;i<keys.length;i++)out.add(Map.of("stage",stage(i),"y",y(i),"cells",sizes[i]));
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
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("stage",stage(on));out.put("y",y(on));out.put("index",on+1);out.put("of",keys.length);out.put("left",wrong);
        if(next!=null)out.put("first",List.of(next.getX(),next.getY(),next.getZ()));
        return out;
    }
}
