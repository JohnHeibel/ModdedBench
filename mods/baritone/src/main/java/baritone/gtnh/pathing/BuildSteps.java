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
     * pending: every cell the plan still fills, later steps too: an empty cell that a later step fills is no side that stays
     * open. now: the ones this pass may fill; only they wait, and only for each other. open: whether a cell that is not
     * pending is a way in (air() below: empty, and not closed in).
     */
    public static Held held(Set<BlockPos> pending,Predicate<BlockPos> open){return held(pending,pending,open);}
    public static Held held(Set<BlockPos> pending,Set<BlockPos> now,Predicate<BlockPos> open) {
        Map<BlockPos,Integer> depth=new HashMap<>();ArrayDeque<BlockPos> queue=new ArrayDeque<>();
        for(BlockPos p:pending)for(int[] side:SIDES){BlockPos n=beside(p,side);if(!pending.contains(n)&&open.test(n)){depth.put(p,0);queue.add(p);break;}}
        if(depth.size()==pending.size())return Held.NONE;
        while(!queue.isEmpty()){
            BlockPos p=queue.poll();int next=depth.get(p)+1;
            for(int[] side:SIDES){BlockPos n=beside(p,side);if(pending.contains(n)&&depth.putIfAbsent(n,next)==null)queue.add(n);}
        }
        Set<BlockPos> held=new HashSet<>(),first=new HashSet<>();
        for(var e:depth.entrySet())if(now.contains(e.getKey()))for(int[] side:SIDES){
            BlockPos n=beside(e.getKey(),side);Integer d=depth.get(n);
            if(d!=null&&d>e.getValue()&&now.contains(n)){held.add(e.getKey());first.add(n);}
        }
        return held.isEmpty()?Held.NONE:new Held(Set.copyOf(held),Set.copyOf(first));
    }
    /**
     * The air that stays air around the cells still to be filled: empty cells no step fills, reached from the player or
     * from the edge of a box AROUND cells wider than those cells (beyond it the world goes on). An empty cell closed in by
     * cells to be filled is not among them: the space kept free over a chest in a lined corner is no way to the corner
     * above it. The box is wider than one cell because a step is one layer: the closed-in space lies in the layer under
     * it, and on the edge of a box one cell around it would count as open.
     */
    private static final int AROUND=3;
    public static Set<BlockPos> air(Set<BlockPos> pending,Predicate<BlockPos> empty,BlockPos... player) {
        if(pending.isEmpty())return Set.of();
        int[] lo={Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE},hi={Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE};
        for(BlockPos p:pending){int[] v={p.getX(),p.getY(),p.getZ()};for(int i=0;i<3;i++){lo[i]=Math.min(lo[i],v[i]-AROUND);hi[i]=Math.max(hi[i],v[i]+AROUND);}}
        Predicate<BlockPos> inside=p->p.getX()>=lo[0]&&p.getX()<=hi[0]&&p.getY()>=lo[1]&&p.getY()<=hi[1]&&p.getZ()>=lo[2]&&p.getZ()<=hi[2];
        Set<BlockPos> air=new HashSet<>();ArrayDeque<BlockPos> queue=new ArrayDeque<>();
        java.util.function.Consumer<BlockPos> reach=p->{if(inside.test(p)&&!pending.contains(p)&&!air.contains(p)&&empty.test(p)){air.add(p);queue.add(p);}};
        for(int x=lo[0];x<=hi[0];x++)for(int y=lo[1];y<=hi[1];y++)for(int z=lo[2];z<=hi[2];z++)
            if(x==lo[0]||x==hi[0]||y==lo[1]||y==hi[1]||z==lo[2]||z==hi[2])reach.accept(new BlockPos(x,y,z));
        for(BlockPos p:player)reach.accept(p);
        while(!queue.isEmpty()){BlockPos p=queue.poll();for(int[] side:SIDES)reach.accept(beside(p,side));}
        return air;
    }
    /** A space this large is not one the next block shuts. */
    private static final int WALK=2048;
    /**
     * Of `fill` (the cells that may be filled now), those whose filling would shut the player in: with that one cell in, no
     * walk leads from `feet` out of the box lo..hi any more. The walk is the plain kind a body can always make: level, down,
     * or up one onto a block with headroom; `open` says a cell holds nothing to bump into. A cell can only be one of them if
     * the walk out that is found now passes through it, so that walk's cells are the ones tried. None when the player is
     * outside the box, in mid-air, or already shut in (nothing is left to keep open). The cells of the player's own body are
     * never among them: they cannot be filled from where it stands, and they are work that waits outside.
     */
    public static Set<BlockPos> shuts(Set<BlockPos> fill,BlockPos feet,Predicate<BlockPos> open,int[] lo,int[] hi) {
        Set<BlockPos> needs=new HashSet<>();
        if(fill.isEmpty()||!walksOut(feet,open,lo,hi,needs))return Set.of();
        Set<BlockPos> shuts=new HashSet<>();
        for(BlockPos c:needs)if(fill.contains(c)&&!c.equals(feet)&&!c.equals(beside(feet,SIDES[2]))&&!walksOut(feet,p->!p.equals(c)&&open.test(p),lo,hi,null))shuts.add(c);
        return shuts;
    }
    /** Whether a walk from `feet` leaves the box; needs (when given) receives the cells that walk passes its body through. */
    private static boolean walksOut(BlockPos feet,Predicate<BlockPos> open,int[] lo,int[] hi,Set<BlockPos> needs) {
        Map<BlockPos,BlockPos> from=new HashMap<>();ArrayDeque<BlockPos> queue=new ArrayDeque<>();
        Predicate<BlockPos> fits=p->open.test(p)&&open.test(beside(p,SIDES[2]));
        if(!fits.test(feet))return false;
        from.put(feet,feet);queue.add(feet);
        while(!queue.isEmpty()){
            BlockPos p=queue.poll();
            if(from.size()>WALK||p.getX()<lo[0]||p.getX()>hi[0]||p.getY()<lo[1]||p.getY()>hi[1]||p.getZ()<lo[2]||p.getZ()>hi[2]){
                if(needs!=null)for(BlockPos q=p;;q=from.get(q)){
                    BlockPos before=from.get(q);needs.add(q);needs.add(beside(q,SIDES[2]));
                    // A step up passes the head through the cell over where it began.
                    if(q.getY()>before.getY())needs.add(new BlockPos(before.getX(),before.getY()+2,before.getZ()));
                    if(before.equals(q))break;
                }
                return true;
            }
            BlockPos below=beside(p,SIDES[3]);
            if(open.test(below)){if(from.putIfAbsent(below,p)==null)queue.add(below);continue;}   // nothing under it: it falls
            for(int[] side:SIDES)if(side[1]==0){
                BlockPos n=beside(p,side),up=beside(n,SIDES[2]);
                BlockPos to=fits.test(n)?n:!open.test(n)&&fits.test(up)&&open.test(new BlockPos(p.getX(),p.getY()+2,p.getZ()))?up:null;
                if(to!=null&&from.putIfAbsent(to,p)==null)queue.add(to);
            }
        }
        return false;
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
