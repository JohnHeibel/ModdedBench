// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package baritone.gtnh;

import baritone.compat.BlockPos;
import baritone.compat.Registry;
import baritone.gtnh.pathing.*;
import baritone.gtnh.pathing.ClickSpace.Voxel;
import dev.modbench.api.ControlRegistry;
import dev.modbench.api.Observations;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.world.World;
import static baritone.gtnh.pathing.WorkSpec.*;

/**
 * The clicks of a build job: its click cells and its uses, one at a time. Copy the blocks around the click
 * (time-sliced), search off the game thread for where it can be made from, walk there, hold the item, sneak or not,
 * look at the face and click through normal input, then check what changed and read what was expected. A job that
 * may break blocks removes the fewest cells that open a view when there is none, and puts the same blocks back
 * (journaled before the first hit, so any later session puts them back first). When the job ends with its controls
 * it puts back what is still out and takes away the scaffold its walks left outside the plan.
 */
final class ClickRun {
    private static final ExecutorService SEARCH=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"modbench-click-search");t.setDaemon(true);return t;});
    /** The game thread's share of one tick for copying blocks. */
    private static final long SLICE=600_000L;
    /** A preview looks at this many clicks, in a copy of at most this many blocks, on the game thread in one go. */
    static final int CHECKED=4*ConstructionPlan.FIRST,PREVIEW=40_000;
    private final ReferenceConstructionProcess job;
    private final Minecraft mc=Minecraft.getMinecraft();
    private final World world;
    private final baritone.Baritone engine;
    private final ConstructionPlan plan;
    final ClickLog log;
    /** Cells removed to open a view and not yet put back; `left` says why one could not be. */
    private final List<Map<String,Object>> restores=new ArrayList<>();
    /** Blocks the job's walks placed outside the plan, with what was placed, and those this session could not take away. */
    private final Map<BlockPos,String> scaffold=new LinkedHashMap<>();
    private final Set<BlockPos> stuck=new HashSet<>();
    /** Ways of making the open click cells, remembered between picks (StepPlan.next); the search thread's while it runs. */
    private final Map<String,List<Vantages.Vantage>> ways=new HashMap<>();
    /** The copy the current step's picks are made in, kept while nothing but the job's own clicks changed it. */
    private ClickSpace space;
    private Set<BlockPos> kept=Set.of();
    private int spaceStep=-1,walkScaffold,made,pressTick,searching;
    private List<StepPlan.Step> open=List.of();
    private String phase="idle";
    private boolean closing;
    private Task task;
    private ClickWorld capture;
    private Future<Found> future;
    private ReferenceNavigationJob nav;
    private InventorySelection selection;
    private StepPlan.Step pressed;
    private BlockPos blame;
    private Map<String,Object> failed=Map.of();

    /** One click on the way: a plan click, a cell removed (to open a view, or one the plan replaces), a removed cell put back, a scaffold block taken away. */
    private static final class Task {
        final String kind;final StepPlan.Step step;final BlockPos block;final Task parent;Map<String,Object> row;
        List<Vantages.Vantage> vantages=List.of();final Set<BlockPos> tried=new HashSet<>();
        Deque<BlockPos> opening;StepPlan.Step hides;Vantages.Vantage at;Read read;
        int age,wait,routes,rounds,expectTries,pressTick=-1;boolean opened,fetched,fetching,variant;
        Map<String,Object> before,clicked,predicted,diagnosis=Map.of(),expectBefore;List<Map<String,Object>> observed;
        Task(String kind,StepPlan.Step step,BlockPos block,Task parent,Map<String,Object> row){this.kind=kind;this.step=step;this.block=block;this.parent=parent;this.row=row;}
        boolean breaking(){return step==null;}
        boolean sneak(){return !breaking()&&step.sneak();}
        Vantages.Target target(){return breaking()?new Vantages.Target(Vantages.using(block,null),null,ClickSpec.Look.ANY,null):step.target();}
        BlockPos pos(){return breaking()?block:step.pos();}
        String label(){return breaking()?(kind.equals("clean")?"scaffold ":"remove ")+block.getX()+","+block.getY()+","+block.getZ():step.label();}
    }
    /** One read in flight. cancel ends it, and nothing that arrives afterwards is kept. */
    static final class Read {
        private volatile Map<String,Object> reply;private volatile boolean cancelled;Runnable end=()->{};
        void reply(Map<String,Object> value){if(!cancelled)reply=value;}
        Map<String,Object> reply(){return reply;}
        void cancel(){if(cancelled)return;cancelled=true;reply=null;end.run();}
    }
    /** step, hides: of a pick. vantages, or the problem with its diagnosis and the cells whose removal would open a view. */
    private record Found(StepPlan.Step step,StepPlan.Step hides,List<Vantages.Vantage> vantages,String problem,Map<String,Object> diagnosis,List<Vantages.Opening> openings) {}

    ClickRun(ReferenceConstructionProcess job,baritone.Baritone engine,ConstructionPlan plan) {
        this.job=job;this.engine=engine;this.plan=plan;world=job.world;
        var saved=job.journal.progress;
        log=new ClickLog(child(saved,"clicks"));
        // Why a cell could not be put back is this session's finding: the next one tries again.
        for(Object row:list(saved.getOrDefault("restore",List.of()))){var r=new LinkedHashMap<>(object(row));r.remove("left");restores.add(r);}
        for(Object row:list(saved.getOrDefault("scaffold",List.of()))){List<?> r=list(row);scaffold.put(pos(r.subList(0,3)),String.valueOf(r.get(3)));}
        if(spread(plan,margin()))throw new IllegalArgumentException(ClickWorld.SPREAD);
    }
    private int margin(){return (int)Math.ceil(mc.playerController.getBlockReachDistance())+2;}
    /** Refused before the job begins, never met half way: a stage whose click cells cannot be looked at in one copy. */
    static boolean spread(ConstructionPlan plan,int margin) {
        for(int step=0;step<plan.steps.count();step++)if(plan.steps.kind(step)==BuildSteps.CLICKS) {
            int stage=plan.steps.stage(step);
            if(StepPlan.volume(plan.places.stream().filter(s->s.stage()==stage).toList(),margin)>ClickWorld.MAX_VOLUME)return true;
        }
        return false;
    }
    boolean settled(String key){return log.settled(key);}
    /** Something to do whatever step the build is on: a click under way, or a cell to put back. */
    boolean busy(){return !phase.equals("idle")||restores.stream().anyMatch(r->!r.containsKey("left"));}
    int made(){return made;}
    String phase(){return phase;}
    /** The cell a stall during a click is about. */
    BlockPos at(){return task!=null?task.pos():open.isEmpty()?null:open.get(0).pos();}
    /** What the stall watchdog sees of a click: every phase moves this while it is getting somewhere, and no longer than its own bound. */
    long activity(){return Objects.hash(log.results.size(),made,restores.size(),scaffold.size(),stuck.size(),phase,task==null?0:System.identityHashCode(task),task==null?0:task.tried.size(),task==null?0:task.age/40,capture==null?-1:capture.cursor(),Math.min(searching,1200)/40);}
    /** A click the game took, from a walk or from the builder, landed outside the plan: a scaffold block to take away again. */
    void placed(BlockPos p){if(!plan.schematic.containsKey(p)&&!baritone.compat.LegacyPlacement.empty(world,p.getX(),p.getY(),p.getZ()))scaffold.put(p,id(p));}

    /** One tick of click work. False: nothing to do now (the step's clicks are made; when closing, all is put back that can be). */
    boolean tick() {
        if(phase.equals("idle"))return next();
        switch(phase) {
            case "capture"->{
                if(!capture.step(SLICE))return true;
                ClickSpace s=capture.space();Set<BlockPos> prot=Set.copyOf(capture.protectedCells);capture=null;
                if(task==null){space=s;kept=prot;spaceStep=job.clickStep();ways.clear();}
                submit(s,prot);
            }
            case "search"->{
                if(!future.isDone()){searching++;return true;}
                Found f=result(future);future=null;searching=0;
                if(task==null){task=new Task("step",f.step(),null,null,null);task.hides=f.hides();}
                task.diagnosis=f.diagnosis();
                if(!f.vantages().isEmpty()){task.vantages=f.vantages();route();return true;}
                // Stances were tried and none gave the click as planned: that, not the lack of others, is what is wrong.
                if(!task.tried.isEmpty()){fail(task.variant?"mismatch":"aim_mismatch");return true;}
                if(!task.opened)for(var o:f.openings())if(o.remove().stream().allMatch(this::restorable)){task.opening=new ArrayDeque<>(o.remove());task.opened=true;breakNext();return true;}
                fail(f.problem());
            }
            case "moving"->{
                nav.tick();if(!nav.done())return true;
                var end=nav.status();nav=null;engine.editAllowed=p->true;
                // The walk may have changed the blocks the picks were made in.
                if(job.allowBreak||scaffold.size()!=walkScaffold)space=null;
                if(task.fetching){task.fetching=false;enter("fetch");return true;}
                if("succeeded".equals(end.get("state"))){enter("select");return true;}
                if(++task.routes<2){observe();return true;}
                task.diagnosis=Map.of("route",String.valueOf(end.get("reason")),"stances",task.vantages.stream().limit(4).map(v->point(v.feet())).toList());
                fail("no_route_from_here");
            }
            // The drop of a cell to put back was walked to: a moment for it to be picked up, then the inventory is asked again.
            case "fetch"->{if(++task.age>20)enter("select");}
            case "select"->select();
            case "selecting"->{job.lease.setKeys(Set.of());if(selection.tick(job.lease)){selection=null;enter(expectBefore()?"expect_before":"aim");}}
            case "expect_before"->{
                var reply=task.read.reply();
                if(reply==null){if(++task.age>200){task.read.cancel();stop("expect_timeout");}return true;}
                task.expectBefore=reply;task.read=null;enter("aim");
            }
            case "aim"->aim();
            case "click"->{
                // One tick with use held is one click; holding longer would repeat it every four ticks.
                if(job.ticks==task.pressTick)return true;
                job.lease.setKeys(task.sneak()?Set.of(mc.gameSettings.keyBindSneak.getKeyCode()):Set.of());enter("settle");
            }
            case "break"->breaking();
            case "settle"->settle();
            case "expect"->expect();
            default->throw new IllegalStateException("unknown click phase "+phase);
        }
        return true;
    }
    private void enter(String next){phase=next;if(task!=null){task.age=0;task.wait=0;}}
    private void idle(){task=null;phase="idle";job.lease.setKeys(Set.of());}
    private static Found result(Future<Found> f) {
        try{return f.get();}catch(ExecutionException e){throw new IllegalStateException("click search failed: "+e.getCause(),e.getCause());}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
    }
    /** What to do next: a cell to put back first; then, closing, scaffold to take away; else the next click of the build's current step. */
    private boolean next() {
        boolean changed=false;
        for(var it=restores.iterator();it.hasNext();) {
            var row=it.next();if(row.containsKey("left"))continue;
            BlockPos p=pos(row.get("pos"));
            if(replaceable(p)){task=new Task("restore",new StepPlan.Step(-1,"restore "+p.getX()+","+p.getY()+","+p.getZ(),true,p,string(row,"id",""),((Number)row.get("meta")).intValue(),Map.of(),ClickSpec.ANY,List.of(),0),null,null,row);observe();return true;}
            // Something stands there already: the same block (it is back), or another.
            if(same(row,p))it.remove();else row.put("left","occupied");
            changed=true;
        }
        if(changed)save();
        if(closing)return clean();
        int step=job.clickStep();if(step>=plan.steps.count())return false;
        int stage=plan.steps.stage(step);
        if(plan.steps.kind(step)==BuildSteps.USES) {
            for(var u:plan.uses)if(u.stage()==stage&&!log.settled(u.key())) {
                Object there=WorkAccess.observed(world,u.pos()).get("id");
                // A session before this one pressed use here and never said what came of it: it is not pressed again.
                if(log.interrupted(u.key())){log.record(u.key(),"unknown");stop(u,"unknown_after_restart",there);return true;}
                if(u.id()!=null&&!u.id().equals(there)){stop(u,"use_target_changed",there);return true;}
                task=new Task("step",u,null,null,null);observe();return true;
            }
            return false;
        }
        if(plan.steps.kind(step)!=BuildSteps.CLICKS)return false;
        List<StepPlan.Step> left=new ArrayList<>();
        for(var s:plan.places)if(s.stage()==stage) {
            if(plan.correct(plan.cells.get(s.index()))){if(!log.settled(s.key())){log.record(s.key(),"already_present");changed=true;}continue;}
            // A block this job clicked in is gone again: it is clicked in again.
            log.drop(s.key());
            if(!plan.occupied(s.pos())){left.add(s);continue;}
            // Another block stands in the cell. The plan replaces it; else the build says occupied once nothing else is left to do.
            if(plan.replace()){task=new Task("break",null,s.pos(),null,null);observe();return true;}
        }
        if(changed)save();
        open=left;if(open.isEmpty())return false;
        observe();return true;
    }
    /** Scaffold to take away, highest first: what stands on a block goes before the block. */
    private boolean clean() {
        BlockPos top=null;
        for(var it=scaffold.entrySet().iterator();it.hasNext();) {
            var e=it.next();BlockPos p=e.getKey();
            if(!e.getValue().equals(id(p))){it.remove();continue;}
            if(!stuck.contains(p)&&(top==null||p.getY()>top.getY()))top=p;
        }
        if(top==null)return false;
        task=new Task("clean",null,top,null,null);observe();return true;
    }
    /** Copy the blocks around what is to be clicked (a pick: around every open click cell of the step), then search. */
    private void observe() {
        if(task==null&&space!=null&&spaceStep==job.clickStep()){submit(space,kept);return;}
        capture=ClickWorld.around(world,task!=null?List.of(task.pos()):open.stream().map(StepPlan.Step::pos).toList(),margin(),job.override);enter("capture");
    }
    private void submit(ClickSpace s,Set<BlockPos> prot) {
        var low=ClickWorld.body(mc,true);var high=ClickWorld.body(mc,false);Set<BlockPos> cells=plan.schematic.keySet();
        // Temporary access is the job's when it may break blocks at all; which cells may go is Access's rule, never a name.
        Predicate<BlockPos> removable=job.allowBreak?p->Access.refusal(s,p,cells,prot::contains)==null:null;
        Task t=task;
        if(t==null){List<StepPlan.Step> o=List.copyOf(open);var known=ways;future=SEARCH.submit(()->pick(o,s,low,high,known,removable));}
        else {
            Set<BlockPos> tried=Set.copyOf(t.tried);var target=t.target();var body=t.sneak()?low:high;BlockPos breaking=t.breaking()?t.block:null;boolean step=t.kind.equals("step");
            future=SEARCH.submit(()->look(s,null,null,target,body,tried,breaking,step?removable:null));
        }
        enter("search");
    }
    /**
     * Pure, off the game thread: which open click cell goes next (StepPlan.next) and where it is clicked from. A cell
     * whose remembered ways are all gone is forgotten and the pick made again, so the answer is for the copy as it is.
     */
    static Found pick(List<StepPlan.Step> open,ClickSpace s,Vantages.Body low,Vantages.Body high,Map<String,List<Vantages.Vantage>> ways,Predicate<BlockPos> removable) {
        for(;;) {
            var p=StepPlan.next(open,s,low,ways);StepPlan.Step c=p.step();
            Found f=look(s,c,p.hides(),c.target(),c.sneak()?low:high,Set.of(),null,p.ready()?null:removable);
            if(!p.ready()||!f.vantages().isEmpty())return f;
            ways.put(c.key(),List.of());
        }
    }
    /**
     * Pure, off the game thread: where one click can be made from, else what is wrong and (for a job that may break)
     * the cells whose removal opens a view. breaking: the block the task removes; it is not stood on unless there is
     * footing under it.
     */
    static Found look(ClickSpace s,StepPlan.Step step,StepPlan.Step hides,Vantages.Target target,Vantages.Body body,Set<BlockPos> tried,BlockPos breaking,Predicate<BlockPos> removable) {
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
    private void route() {
        BlockPos feet=WorkAccess.feet();
        if(mc.thePlayer.onGround&&task.vantages.stream().anyMatch(v->v.feet().equals(feet))){enter("select");return;}
        walk(task.vantages.stream().map(Vantages.Vantage::feet).distinct().toList());
    }
    /**
     * A walk under the job's lease. It never places into or breaks a plan cell, finished or not, nor a cell that is
     * out for access; outside the plan the job's terrain permissions decide. Taking scaffold away it places nothing
     * and may break scaffold only, which is how it comes down from a pillar.
     */
    private void walk(List<BlockPos> goals) {
        job.lease.setKeys(Set.of());
        boolean clean=task.kind.equals("clean");var cells=plan.schematic;Set<BlockPos> mine=Set.copyOf(scaffold.keySet()),out=new HashSet<>();
        for(var row:restores)out.add(pos(row.get("pos")));
        engine.editAllowed=clean?mine::contains:p->!cells.containsKey(p)&&!out.contains(p);
        walkScaffold=scaffold.size();
        nav=new ReferenceNavigationJob(engine,goals,Math.max(1,job.remaining),clean||job.allowBreak,!clean&&job.allowPlace,job.override,job.lease,null,0);enter("moving");
    }
    private void breakNext() {
        BlockPos cell=task.opening.poll();
        if(cell==null){task.opening=null;task.tried.clear();task.routes=0;observe();return;}
        task=new Task("break",null,cell,task,null);observe();
    }
    private void select() {
        job.lease.setKeys(Set.of());int slot;StepPlan.Step s=task.step;
        if(task.breaking()) {
            var choice=MiningTools.choose(world,task.block,null);
            // A cell out for access comes back as the same block: its drop is needed unless one is carried.
            if(choice.get("bestSlot")==null||task.parent!=null&&!Boolean.TRUE.equals(choice.get("harvestable"))&&slot(world.getBlock(task.block.getX(),task.block.getY(),task.block.getZ()),world.getBlockMetadata(task.block.getX(),task.block.getY(),task.block.getZ()))<0){fail("access_failed");return;}
            slot=(Integer)choice.get("bestSlot");
        }else if(task.kind.equals("restore")) {
            slot=slot(Registry.block(s.id()),s.meta());
            if(slot<0&&!task.fetched){task.fetched=true;BlockPos drop=drop(Registry.block(s.id()),s.meta(),s.pos());if(drop!=null){task.fetching=true;walk(List.of(drop));return;}}
            if(slot<0){fail("no_item");return;}
        }else if(s.place()) {
            slot=plan.slot(plan.cells.get(s.index()));
            if(slot<0){failed=row(task,"missing_materials");job.shortOf(s.pos());return;}
        }else if(s.item().containsKey("empty")) {
            if(mc.thePlayer.getHeldItem()!=null) {
                int empty=-1;for(int i=0;i<9&&empty<0;i++)if(mc.thePlayer.inventory.mainInventory[i]==null)empty=i;
                if(empty<0){stop("no_empty_hand");return;}
                mc.thePlayer.inventory.currentItem=empty;
            }
            enter(expectBefore()?"expect_before":"aim");return;
        }else {
            slot=-1;for(int i=0;i<36&&slot<0;i++)if(WorkAccess.item(mc.thePlayer.inventory.mainInventory[i],s.item()))slot=i;
            if(slot<0){failed=row(task,"missing_materials");job.shortOf(s.pos());return;}
        }
        if(slot==mc.thePlayer.inventory.currentItem&&mc.thePlayer.getHeldItem()!=null){enter(expectBefore()?"expect_before":"aim");return;}
        selection=new InventorySelection(slot);enter("selecting");
    }
    private static boolean places(ItemStack st,Block block,int meta){return st!=null&&st.stackSize>0&&st.getItem() instanceof ItemBlock&&Block.getBlockFromItem(st.getItem())==block&&st.getItem().getMetadata(st.getItemDamage())==meta;}
    /** The inventory slot of a stack that places exactly this block, or -1. */
    private int slot(Block block,int meta){for(int i=0;i<36;i++)if(places(mc.thePlayer.inventory.mainInventory[i],block,meta))return i;return -1;}
    /** Where the nearest dropped stack of exactly this block lies, within 8 blocks of the cell it came out of. */
    private BlockPos drop(Block block,int meta,BlockPos from) {
        net.minecraft.entity.item.EntityItem best=null;
        for(Object o:world.loadedEntityList)if(o instanceof net.minecraft.entity.item.EntityItem e&&!e.isDead&&places(e.getEntityItem(),block,meta)&&e.getDistanceSq(from.getX()+.5,from.getY()+.5,from.getZ()+.5)<=64
                &&(best==null||e.getDistanceSqToEntity(mc.thePlayer)<best.getDistanceSqToEntity(mc.thePlayer)))best=e;
        return best==null?null:new BlockPos((int)Math.floor(best.posX),(int)Math.floor(best.boundingBox.minY+.001),(int)Math.floor(best.posZ));
    }
    /** A cell may go for access only when the same block can come back: one is carried, or the block says it drops itself. */
    private boolean restorable(BlockPos p) {
        Block b=world.getBlock(p.getX(),p.getY(),p.getZ());int meta=world.getBlockMetadata(p.getX(),p.getY(),p.getZ());
        if(slot(b,meta)>=0)return true;
        var drops=b.getDrops(world,p.getX(),p.getY(),p.getZ(),meta,0);
        return drops.size()==1&&places(drops.get(0),b,meta);
    }
    private boolean expectBefore() {
        if(task.breaking()||task.expectBefore!=null||task.step.expect().stream().noneMatch(e->e.op().equals("changed")))return false;
        task.read=read(task.step.expect());return task.read!=null;
    }
    private static Read read(List<Expectation> expect) {
        Observations o=Observations.Registry.get();if(o==null)return null;
        Map<String,Map<String,Object>> queries=new LinkedHashMap<>();
        for(int i=0;i<expect.size();i++)queries.put("e"+i,Map.of("method",expect.get(i).method(),"params",expect.get(i).request()));
        Read r=new Read();r.end=o.read(queries,r::reply);return r;
    }
    private static double round(double v){return Math.round(v*1000)/1000.0;}
    private void aim() {
        BlockPos feet=WorkAccess.feet();
        Vantages.Vantage v=task.vantages.stream().filter(x->x.feet().equals(feet)).findFirst().orElse(null);
        if(v==null){if(task.vantages.isEmpty()){observe();return;}v=task.vantages.get(0);}
        task.at=v;
        var eye=mc.thePlayer.getPosition(1);
        double dx=v.point().x()-eye.xCoord,dy=v.point().y()-eye.yCoord,dz=v.point().z()-eye.zCoord;
        float yaw=(float)Math.toDegrees(Math.atan2(-dx,dz)),pitch=(float)-Math.toDegrees(Math.atan2(dy,Math.hypot(dx,dz)));
        Set<Integer> keys=task.sneak()?Set.of(mc.gameSettings.keyBindSneak.getKeyCode()):Set.of();
        job.lease.look(yaw,pitch);job.lease.setKeys(keys);
        ClickSpec.Look look=task.breaking()?ClickSpec.Look.ANY:task.step.click().look();
        boolean ready=look.accepts(mc.thePlayer.rotationYaw,mc.thePlayer.rotationPitch)&&mc.thePlayer.isSneaking()==task.sneak()
            &&Math.hypot(mc.thePlayer.motionX,mc.thePlayer.motionZ)<.02&&mc.thePlayer.onGround;
        ControlRegistry.targeting().refresh();var hit=mc.objectMouseOver;BlockPos b=v.click().block();
        boolean on=hit!=null&&hit.typeOfHit==MovingObjectPosition.MovingObjectType.BLOCK&&hit.blockX==b.getX()&&hit.blockY==b.getY()&&hit.blockZ==b.getZ()
            &&(task.breaking()||hit.sideHit==v.click().face())
            &&(task.breaking()||task.step.click().hit()==null||Math.abs(hit.hitVec.xCoord-v.point().x())+Math.abs(hit.hitVec.yCoord-v.point().y())+Math.abs(hit.hitVec.zCoord-v.point().z())<.06);
        if(on&&ready) {
            StepPlan.Step s=task.step;
            if(!task.breaking()&&s.place()) {
                var predicted=baritone.compat.LegacyPlacement.predict(engine.getPlayerContext(),mc.thePlayer.getHeldItem(),baritone.compat.RayTraceResult.fromNative(hit),new baritone.api.utils.Rotation(mc.thePlayer.rotationYaw,mc.thePlayer.rotationPitch));
                task.predicted=Map.of("id",String.valueOf(Registry.name(predicted.getBlock())),"meta",predicted.meta);
                // The click from here would make another variant than the one the cell names: nothing is placed from this stance.
                if(task.kind.equals("step")&&s.meta()!=null&&s.id().equals(task.predicted.get("id"))&&predicted.meta!=s.meta()){task.variant=true;another();return;}
            }
            task.clicked=new LinkedHashMap<>();task.clicked.put("block",point(b));task.clicked.put("face",ClickSpec.NAMES[hit.sideHit]);
            task.clicked.put("hit",List.of(round(hit.hitVec.xCoord-hit.blockX),round(hit.hitVec.yCoord-hit.blockY),round(hit.hitVec.zCoord-hit.blockZ)));
            task.clicked.put("yaw",round(mc.thePlayer.rotationYaw));task.clicked.put("pitch",round(mc.thePlayer.rotationPitch));task.clicked.put("sneak",task.sneak());task.clicked.put("stand",point(feet));
            task.before=state(task.pos());
            if(task.breaking()) {
                // Journaled before the first hit, so whatever ends this session the next puts it back.
                if(task.parent!=null&&task.row==null){task.row=new LinkedHashMap<>();task.row.put("pos",point(task.block));task.row.put("id",task.before.get("id"));task.row.put("meta",task.before.get("meta"));task.row.put("for",task.parent.label());restores.add(task.row);save();}
                space=null;enter("break");job.lease.setKeys(Set.of());return;
            }
            // A use is journaled as being taken before the key goes down: a session that ends here never presses it twice.
            if(task.kind.equals("step")&&!s.place()){log.record(s.key(),ClickLog.TAKING);save();}
            pressed=s;pressTick=job.ticks;
            Set<Integer> press=new HashSet<>(keys);press.add(mc.gameSettings.keyBindUseItem.getKeyCode());job.lease.setKeys(press);
            task.pressTick=job.ticks;enter("click");return;
        }
        if(++task.age>30)another();
    }
    /** This stance does not give the click as planned (the body is off-centre, something is in the way, the block would face another way): the others. */
    private void another() {
        var hit=mc.objectMouseOver;boolean block=hit!=null&&hit.typeOfHit==MovingObjectPosition.MovingObjectType.BLOCK;
        task.diagnosis=Map.of("aimedAt",Map.of("block",point(task.at.click().block()),"face",ClickSpec.NAMES[task.at.click().face()],"stand",point(task.at.feet())),
            "nativeHit",block?Map.of("pos",List.of(hit.blockX,hit.blockY,hit.blockZ),"face",ClickSpec.NAMES[hit.sideHit]):"none");
        task.tried.add(task.at.feet());task.age=0;
        task.vantages=task.vantages.stream().filter(x->!task.tried.contains(x.feet())).toList();
        if(!task.vantages.isEmpty()){route();return;}
        if(task.tried.size()<16){observe();return;}
        fail(task.variant?"mismatch":"aim_mismatch");
    }
    private void breaking() {
        if(++task.age>400){fail("access_failed");return;}
        if(task.age==1){job.lease.setKeys(Set.of());return;} // a released tick first: focus changes suppress the next attack
        if(replaceable(task.block)) {
            job.lease.setKeys(Set.of());if(++task.wait<4)return;
            Task t=task;
            if(t.kind.equals("clean")){scaffold.remove(t.block);save();idle();}
            else if(t.parent==null)idle(); // the plan replaces what stood here: the cell is open for its click now
            else{task=t.parent;breakNext();}
            return;
        }
        ControlRegistry.targeting().refresh();var hit=mc.objectMouseOver;
        boolean on=hit!=null&&hit.typeOfHit==MovingObjectPosition.MovingObjectType.BLOCK&&hit.blockX==task.block.getX()&&hit.blockY==task.block.getY()&&hit.blockZ==task.block.getZ();
        job.lease.setKeys(on?Set.of(mc.gameSettings.keyBindAttack.getKeyCode()):Set.of());
        if(!on&&task.age>40){mc.playerController.resetBlockRemoving();if(++task.rounds>=3)fail("access_failed");else enter("aim");}
    }
    private void settle() {
        task.age++;StepPlan.Step s=task.step;
        if(s.place()) {
            if(replaceable(task.pos())) {
                // The game took no block: from another stance, a few times, then the click is what is wrong.
                if(task.age>20){if(++task.rounds>=3)fail("placement_rejected");else another();}
                return;
            }
            if(task.age<2)return;
            // What stands there is another variant than the plan's: the server's word may still be on its way, briefly.
            if(task.kind.equals("step")&&!plan.correct(plan.cells.get(s.index()))){if(task.age>10)stop("mismatch");return;}
        }else if(task.age<4)return;
        if(!s.expect().isEmpty()){task.read=null;task.expectTries=0;enter("expect");task.wait=-1;return;}
        // A tile entity can keep a facing or a side outside block and metadata, so those do not confirm what the click set.
        record(!s.place()?"used":Boolean.TRUE.equals(state(task.pos()).get("hasTile"))&&s.click().orients()?"unverified":"verified");
    }
    private void expect() {
        task.age++;StepPlan.Step s=task.step;
        if(task.read==null) {
            if(++task.wait<0)return;
            task.read=read(s.expect());task.wait=0;
            if(task.read==null)record("unverified"); // nothing to read through here: made, not confirmed
            return;
        }
        var reply=task.read.reply();
        if(reply==null){if(++task.wait>200){task.read.cancel();log.record(s.key(),"unverified");made++;stop("expect_timeout");}return;}
        List<Map<String,Object>> observed=new ArrayList<>();boolean all=true;
        var values=child(reply,"values");var before=task.expectBefore==null?Map.<String,Object>of():child(task.expectBefore,"values");
        for(int i=0;i<s.expect().size();i++) {
            Expectation e=s.expect().get(i);
            Object value=values.containsKey("e"+i)?Expectation.extract(values.get("e"+i),e.path()):Expectation.MISSING;
            Object prior=before.containsKey("e"+i)?Expectation.extract(before.get("e"+i),e.path()):Expectation.MISSING;
            boolean met=e.met(prior,value);all&=met;
            Map<String,Object> row=new LinkedHashMap<>(e.json());row.put("observed",value==Expectation.MISSING?"missing":value);
            if(e.op().equals("changed"))row.put("before",prior==Expectation.MISSING?"missing":prior);
            if(child(reply,"errors").containsKey("e"+i))row.put("error",child(reply,"errors").get("e"+i));
            if(reply.containsKey("error"))row.put("error",reply.get("error"));
            row.put("met",met);observed.add(row);
        }
        task.observed=observed;task.read=null;
        if(all){record("verified");return;}
        if(task.expectTries++==0){task.wait=-20;return;} // read once more, 20 ticks later; the click is not made again
        log.record(s.key(),"expect_failed");made++;stop("expect_failed");
    }
    /** The click is made and checked: journaled, and the picks' copy and remembered ways brought up to date. */
    private void record(String verdict) {
        Task t=task;BlockPos p=t.pos();
        if(t.kind.equals("restore")) {
            if(same(t.row,p))restores.remove(t.row);else t.row.put("left","differs");
            space=null;save();idle();return;
        }
        log.record(t.step.key(),verdict);made++;job.journal.recordClick(row(t,verdict));
        if(t.step.place()){if(space!=null)space=space.with(p,ClickWorld.voxel(world,p.getX(),p.getY(),p.getZ()));StepPlan.placed(ways,open,p,ClickWorld.body(mc,true));}
        save();idle();
    }
    /** The task cannot be done. A plan click stops the job; what the job does on its own account is left, listed in the receipt, and the job goes on. */
    private void fail(String reason) {
        Task t=task;
        switch(t.kind) {
            case "restore"->{t.row.put("left",reason);save();idle();}
            case "clean"->{stuck.add(t.block);idle();}
            case "break"->{
                if(t.parent==null){stop("occupied");return;}
                Map<String,Object> why=new LinkedHashMap<>(t.parent.diagnosis);why.put("access",Map.of("remove",point(t.block),"present",id(t.block),"why",reason));
                task=t.parent;task.diagnosis=why;stop("access_failed");
            }
            default->stop(reason);
        }
    }
    /** A plan click cannot be made, or did not do what was asked: the job stops on it, with the click in full. */
    private void stop(String reason){Task t=task;failed=row(t,reason);job.journal.recordClick(failed);job.stop(reason,t.pos());}
    private void stop(StepPlan.Step s,String reason,Object present) {
        failed=new LinkedHashMap<>();failed.put("click",s.label());failed.put("pos",point(s.pos()));failed.put("result",reason);failed.put("present",present);
        job.journal.recordClick(failed);save();job.stop(reason,s.pos());
    }
    /** One click in full: what was asked, what was clicked and from where, the cell before and after, what was read, and for a click with no stance what is in the way. */
    private Map<String,Object> row(Task t,String result) {
        Map<String,Object> out=new LinkedHashMap<>();out.put("click",t.label());out.put("pos",point(t.pos()));out.put("result",result);
        if(t.step!=null&&!t.step.click().json().isEmpty())out.put("asked",t.step.click().json());
        if(t.clicked!=null)out.put("clicked",t.clicked);
        if(t.before!=null){out.put("before",t.before);out.put("after",state(t.pos()));}
        if(t.predicted!=null)out.put("predicted",t.predicted);
        if(t.observed!=null)out.put("expect",t.observed);
        if(t.hides!=null)out.put("hides",t.hides.label());
        if(t.clicked==null||!t.diagnosis.containsKey("clicks"))out.putAll(t.diagnosis);
        return out;
    }
    private String id(BlockPos p){Block b=world.getBlock(p.getX(),p.getY(),p.getZ());return b.isAir(world,p.getX(),p.getY(),p.getZ())?"minecraft:air":String.valueOf(Registry.name(b));}
    private Map<String,Object> state(BlockPos p) {
        Map<String,Object> out=new LinkedHashMap<>();int meta=world.getBlockMetadata(p.getX(),p.getY(),p.getZ());
        out.put("id",id(p));out.put("meta",meta);out.put("hasTile",world.getBlock(p.getX(),p.getY(),p.getZ()).hasTileEntity(meta));return out;
    }
    private boolean replaceable(BlockPos p){return baritone.compat.LegacyPlacement.empty(world,p.getX(),p.getY(),p.getZ());}
    private boolean same(Map<String,Object> row,BlockPos p){return id(p).equals(row.get("id"))&&world.getBlockMetadata(p.getX(),p.getY(),p.getZ())==((Number)row.get("meta")).intValue();}
    private void sync() {
        var saved=job.journal.progress;saved.put("clicks",log.results);saved.put("restore",restores);
        saved.put("scaffold",scaffold.entrySet().stream().map(e->List.<Object>of(e.getKey().getX(),e.getKey().getY(),e.getKey().getZ(),e.getValue())).toList());
    }
    private void save(){job.journal.save(job.status());}

    /**
     * A screen the job does not own opened. True when a click of this job opened it: the screen is closed, a use is
     * journaled as made (it is not pressed again), and the build stops on that click.
     */
    boolean guiOpened() {
        if(pressed==null||job.ticks-pressTick>40)return false;
        StepPlan.Step s=pressed;pressed=null;
        mc.thePlayer.closeScreen();
        failed=task!=null&&task.step==s?row(task,"gui_opened"):new LinkedHashMap<>(Map.of("click",s.label(),"pos",point(s.pos()),"result","gui_opened"));
        if(!s.place()){log.record(s.key(),"gui_opened");made++;}
        job.journal.recordClick(failed);blame=s.pos();abort();return true;
    }
    BlockPos blame(){return blame;}
    /** Whatever is in flight is dropped: the walk, the selection, the search, the read. A use already pressed is journaled as made. */
    private void abort() {
        if(nav!=null&&!nav.done())nav.cancel("job_ended");
        nav=null;engine.editAllowed=p->true;
        if(selection!=null){selection.close();selection=null;}
        if(future!=null){future.cancel(true);future=null;}
        capture=null;
        if(task!=null) {
            if(task.read!=null)task.read.cancel();
            if(task.kind.equals("step")&&log.taking(task.step.key())){log.record(task.step.key(),"unverified");made++;}
        }
        task=null;phase="idle";
    }
    /** The job is ending with its controls: what is in flight is dropped. True when something is to be put back or taken away first. */
    boolean close() {
        abort();closing=true;space=null;job.lease.setKeys(Set.of());
        return restores.stream().anyMatch(r->!r.containsKey("left"))||!scaffold.isEmpty();
    }
    /** Ticks the closing may take: walks to each cell and the clicks. */
    int budget(){return Math.min(6000,1200+100*(restores.size()+scaffold.size()));}
    void release() {
        abort();
        if(mc.theWorld==world)scaffold.entrySet().removeIf(e->!e.getValue().equals(id(e.getKey())));
    }
    /** The receipt: counts, the first few unverified clicks, the failed click in full on a stop, and what a finished job left out or standing. */
    void status(Map<String,Object> out,Map<String,Object> stopped) {
        sync();
        int of=plan.places.size()+plan.uses.size();
        if(of>0) {
            var counts=log.counts();counts.put("of",of);
            List<Map<String,Object>> some=new ArrayList<>();
            for(var list:List.of(plan.places,plan.uses))for(var s:list)if(some.size()<ConstructionPlan.FIRST&&log.unverified(s.key()))some.add(Map.of("click",s.label(),"result",log.of(s.key())));
            if(!some.isEmpty())counts.put("unverifiedFirst",some);
            out.put("clicks",counts);
        }
        if(stopped!=null&&!failed.isEmpty())stopped.put("click",failed);
        if(!job.done())return;
        if(!restores.isEmpty())out.put("accessLeft",Map.of("count",restores.size(),"first",restores.stream().limit(ConstructionPlan.FIRST).toList()));
        if(!scaffold.isEmpty())out.put("scaffoldLeft",Map.of("count",scaffold.size(),"first",scaffold.keySet().stream().limit(ConstructionPlan.FIRST).map(WorkSpec::point).toList()));
    }

    /**
     * For a preview: whether the plan's click cells and uses have a stance, each in the world as the build will have
     * made it by then (the plain cells of its stage and the stages before standing, as full blocks, and the clicks
     * before it made), with the job's reason words. Bounded: the first CHECKED clicks, in a copy of at most PREVIEW
     * blocks made on the game thread in one go, and the first few problems.
     */
    static Map<String,Object> preview(ConstructionPlan plan,boolean override) {
        var mc=Minecraft.getMinecraft();List<StepPlan.Step> all=new ArrayList<>(plan.places);all.addAll(plan.uses);
        all.sort(Comparator.comparingInt(StepPlan.Step::stage));
        Map<String,Object> out=new LinkedHashMap<>();out.put("count",all.size());
        List<StepPlan.Step> checked=all.subList(0,Math.min(CHECKED,all.size()));int margin=(int)Math.ceil(mc.playerController.getBlockReachDistance())+2;
        if(StepPlan.volume(checked,margin)>PREVIEW){out.put("checked",0);out.put("skipped","the clicks lie too far apart to look at in a preview; the job looks at each stage's");return out;}
        ClickWorld copy=ClickWorld.around(plan.world,checked.stream().map(StepPlan.Step::pos).toList(),margin,override);
        while(!copy.step(Long.MAX_VALUE)){}
        ClickSpace w=copy.space();var low=ClickWorld.body(mc,true);var high=ClickWorld.body(mc,false);
        int ready=0,built=-1;List<Map<String,Object>> problems=new ArrayList<>();
        for(int stage:checked.stream().mapToInt(StepPlan.Step::stage).distinct().toArray()) {
            Map<BlockPos,Voxel> plain=new HashMap<>();
            for(Cell c:plan.cells)if(c.click()==null&&c.stage()>built&&c.stage()<=stage)plain.put(c.pos(),c.clear()?Voxel.air():Voxel.full(c.pos(),c.id(),false));
            w=w.with(plain);built=stage;
            List<StepPlan.Step> open=new ArrayList<>();Map<String,List<Vantages.Vantage>> ways=new HashMap<>();
            for(var s:checked)if(s.stage()==stage&&s.place()){if(plan.correct(plan.cells.get(s.index())))ready++;else open.add(s);}
            while(!open.isEmpty()) {
                var p=StepPlan.next(open,w,low,ways);
                if(!p.ready()){for(var s:open)problems.add(problem(w,s,low));break;}
                open.remove(p.step());ready++;w=w.with(p.step().pos(),Voxel.full(p.step().pos(),p.step().id(),false));StepPlan.placed(ways,open,p.step().pos(),low);
            }
            for(var s:checked)if(s.stage()==stage&&!s.place()) {
                if(s.id()!=null&&!s.id().equals(w.at(s.pos()).id())){Map<String,Object> row=new LinkedHashMap<>();row.put("click",s.label());row.put("pos",point(s.pos()));row.put("reason","use_target_changed");row.put("present",w.at(s.pos()).id());problems.add(row);}
                else if(Vantages.search(w,s.target(),s.sneak()?low:high,1).isEmpty())problems.add(problem(w,s,s.sneak()?low:high));
                else ready++;
            }
        }
        out.put("checked",checked.size());out.put("ready",ready);out.put("problemCount",problems.size());out.put("problems",problems.subList(0,Math.min(ConstructionPlan.FIRST,problems.size())));
        return out;
    }
    private static Map<String,Object> problem(ClickSpace w,StepPlan.Step s,Vantages.Body body) {
        Map<String,Object> row=new LinkedHashMap<>();row.put("click",s.label());row.put("pos",point(s.pos()));
        if(s.place()&&!w.at(s.pos()).replaceable()){row.put("reason","occupied");row.put("present",w.at(s.pos()).id());return row;}
        Found f=look(w,s,null,s.target(),body,Set.of(),null,null);
        row.put("reason",f.problem());row.put("blocking",f.diagnosis().get("blocking"));return row;
    }
}
