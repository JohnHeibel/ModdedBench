// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package baritone.gtnh;

import baritone.compat.BlockPos;
import baritone.compat.Registry;
import baritone.gtnh.pathing.*;
import baritone.gtnh.pathing.ClickSpec.Vec;
import dev.modbench.api.Observations;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.block.Block;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MovingObjectPosition;
import static baritone.gtnh.pathing.WorkSpec.*;

/**
 * A build given as click steps. Each step: copy the blocks around it (time-sliced), search off the game thread for where
 * the click can be made from, walk there, hold the item, sneak or not, look at the face and click through normal input,
 * then check what changed and read the step's expectations. With temporary access allowed, a step with no vantage removes
 * the fewest cells that open one and puts them back afterwards (journaled, so a resume puts them back first).
 */
final class ClickStepProcess extends BulkJob {
    private static final ExecutorService SEARCH=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"modbench-click-search");t.setDaemon(true);return t;});
    private static final long SLICE=600_000L;
    private final baritone.Baritone engine;
    private final List<StepPlan.Step> steps;
    private final BlockPos origin;
    private final Access access;
    private final Set<BlockPos> planned=new HashSet<>();
    private final Map<Integer,Map<String,Object>> receipts=new LinkedHashMap<>();
    private final List<Map<String,Object>> restores=new ArrayList<>();
    private final List<Map<String,Object>> accessLog=new ArrayList<>();
    private List<Integer> sequence=List.of();
    private Map<String,Object> orderReport=Map.of(),diagnosis=Map.of(),failedStep=Map.of();
    private String phase="capturing";
    private ClickWorld capture;
    private Future<?> future;
    private Task task;
    private ReferenceNavigationJob nav;
    private InventorySelection selection;
    private String[] endingWith;

    /** One click on the way: a plan step, a cell removed for access, or a removed cell put back. */
    private static final class Task {
        final String kind;final StepPlan.Step step;final BlockPos block;final Task parent;Map<String,Object> restore;
        List<Vantages.Vantage> vantages=List.of();Set<BlockPos> tried=new HashSet<>();
        Deque<BlockPos> opening;ClickSpace space;
        int aim,settle,routes,hold,expectTries,wait,pressTick=-1;boolean opened;
        Vantages.Vantage at;String stackRule;Map<String,Object> before,clicked,predicted,substitute;
        Map<String,Object> expectBefore;AtomicReference<Map<String,Object>> reply;List<Map<String,Object>> observed;
        Task(String kind,StepPlan.Step step,BlockPos block,Map<String,Object> restore,Task parent){this.kind=kind;this.step=step;this.block=block;this.restore=restore;this.parent=parent;}
        boolean breaking(){return kind.equals("break");}
        boolean sneak(){return breaking()?false:step.click().sneak()!=null?step.click().sneak():step.place();}
        Vantages.Target target(){return breaking()?new Vantages.Target(Vantages.using(block,null),null,ClickSpec.Look.ANY,null):step.target();}
        BlockPos pos(){return breaking()?block:step.pos();}
        String label(){return breaking()?"remove "+block.getX()+","+block.getY()+","+block.getZ():step.label();}
    }
    private record Searched(List<Vantages.Vantage> vantages,String problem,Map<String,Object> diagnosis,List<Vantages.Opening> openings) {}
    private record Ordered(StepPlan.Order order,Map<Integer,Map<String,Object>> diagnoses) {}
    /** Pure: the order, and for each step it found no way to make, the diagnosis in the world the steps before it leave. */
    private static Ordered order(List<StepPlan.Step> open,ClickSpace space,Vantages.Body body) {
        var order=StepPlan.order(open,space,body,2000);Map<Integer,Map<String,Object>> diagnoses=new HashMap<>();
        ClickSpace w=space;
        for(var s:order.steps()) {
            if(order.impossible().containsKey(s.index())) {
                var tally=new Vantages.Tally();Vantages.search(w,s.target(),body,1,tally);
                diagnoses.put(s.index(),diagnosis(w,s.target(),tally,order.impossible().get(s.index()),s.label()));
            }else if(s.place())w=w.with(s.pos(),ClickSpace.Voxel.full(s.pos(),s.id(),false));
        }
        return new Ordered(order,diagnoses);
    }

    ClickStepProcess(BaritoneNavigation navigation,WorkJournal journal,Map<String,Object> options) {
        super(navigation,journal,options);engine=navigation.reference();
        steps=StepPlan.parse(journal.spec);origin=journal.spec.containsKey("origin")?pos(journal.spec.get("origin")):new BlockPos(0,0,0);
        access=journal.spec.containsKey("access")?Access.parse(child(journal.spec,"access")):Access.OFF;
        steps.stream().filter(StepPlan.Step::place).forEach(s->planned.add(s.pos()));
        for(var e:child(journal.progress,"steps").entrySet())receipts.put(Integer.parseInt(e.getKey()),object(e.getValue()));
        for(Object row:list(journal.progress.getOrDefault("restore",List.of())))restores.add(new LinkedHashMap<>(object(row)));
        for(Object row:list(journal.progress.getOrDefault("accessLog",List.of())))accessLog.add(object(row));
    }
    @Override void begin() {
        super.begin();if(done())return;
        engine.getPathingBehavior().forceCancel();
        for(StepPlan.Step s:steps) {
            if(receipts.containsKey(s.index()))continue;
            if(!ForgeSnapshot.loaded(world,s.pos().getX(),s.pos().getY(),s.pos().getZ())){finish("failed","unloaded: "+s.label());return;}
            String p=s.place()?WorkAccess.protection(s.pos(),override):null;
            if(p!=null){finish("failed","protected: "+s.label()+" ("+p+")");return;}
        }
        int margin=(int)Math.ceil(mc.playerController.getBlockReachDistance())+2;
        capture=ClickWorld.around(world,steps.stream().map(StepPlan.Step::pos).toList(),margin,override);
    }
    @Override String phase(){return phase;}
    @Override int progress(){return receipts.size();}
    @Override long activity(){return Objects.hash(receipts.size(),restores.size(),phase,task==null?0:System.identityHashCode(task),task==null?0:task.tried.size());}
    @Override void step() {
        switch(phase) {
            case "capturing"->{
                if(!capture.step(SLICE))return;
                ClickSpace space=capture.space();capture=null;
                List<StepPlan.Step> open=new ArrayList<>();
                for(StepPlan.Step s:steps)if(!receipts.containsKey(s.index())) {
                    if(s.place()&&present(s)){receipts.put(s.index(),receipt(s,"already_present",null));continue;}
                    open.add(s);
                }
                var body=ClickWorld.body(mc,true);
                future=SEARCH.submit(()->order(open,space,body));phase="ordering";
            }
            case "ordering"->{
                if(!future.isDone())return;
                Ordered ordered=result(future);future=null;StepPlan.Order order=ordered.order();
                sequence=order.steps().stream().map(StepPlan.Step::index).toList();
                orderReport=new LinkedHashMap<>(order.json());
                for(var e:order.impossible().entrySet()) {
                    StepPlan.Step s=steps.get(e.getKey());
                    if(e.getValue().equals("occupied")){finish("failed","occupied: "+s.label()+" holds "+WorkAccess.observed(world,s.pos()).get("id"));return;}
                    // A view no earlier step can open: the only remedy is access the job may take, so without it say so now.
                    boolean fixable=access.allowed()&&!e.getValue().equals("support_missing")&&!e.getValue().equals("hit_not_on_face");
                    if(!fixable){diagnosis=ordered.diagnoses().getOrDefault(e.getKey(),Map.of("step",s.label(),"problem",e.getValue()));finish("failed",e.getValue()+": "+s.label()+blocked(diagnosis));return;}
                }
                journal.progress.put("order",orderReport);phase="next";
            }
            case "next"->next();
            case "observe"->{
                if(capture==null)capture=ClickWorld.around(world,List.of(task.pos()),(int)Math.ceil(mc.playerController.getBlockReachDistance())+2,override);
                if(!capture.step(SLICE))return;
                ClickSpace space=capture.space();Set<BlockPos> prot=Set.copyOf(capture.protectedCells);capture=null;task.space=space;
                Task t=task;var body=ClickWorld.body(mc,t.sneak());boolean mayOpen=access.allowed()&&t.kind.equals("step")&&t.parent==null;
                future=SEARCH.submit(()->search(space,t,body,mayOpen?access:null,prot,planned));phase="searching";
            }
            case "searching"->{
                if(!future.isDone())return;
                Searched found=result(future);future=null;
                if(!found.vantages().isEmpty()){task.vantages=found.vantages();route();return;}
                if(!found.openings().isEmpty()&&!task.opened){open(found.openings().get(0));return;}
                diagnosis=found.diagnosis();
                finish("failed",(task.opened?"no_vantage_after_access":found.problem())+": "+task.label()+blocked(diagnosis));
            }
            case "moving"->{
                nav.tick();if(!nav.done())return;
                var navStatus=nav.status();nav=null;
                if("succeeded".equals(navStatus.get("state"))){phase="select";return;}
                if(++task.routes<2){phase="observe";return;}
                diagnosis=Map.of("step",task.label(),"route",navStatus.getOrDefault("reason",""),"vantages",task.vantages.stream().map(ClickStepProcess::vantage).toList());
                finish("failed","no_route_from_here: "+task.label()+" ("+navStatus.get("reason")+")");
            }
            case "select"->select();
            case "selecting"->{lease.setKeys(Set.of());if(selection.tick(lease)){selection=null;phase=task.breaking()?"aim":expectBefore()?"expect_before":"aim";}}
            case "expect_before"->{
                var reply=task.reply.get();if(reply==null){if(++task.wait>200)finish("failed","expectation_read_timeout: "+task.label());return;}
                task.expectBefore=reply;task.reply=null;phase="aim";
            }
            case "aim"->aim();
            case "click"->{
                // One tick with use held is one click; holding longer would repeat it every four ticks.
                if(ticks==task.pressTick)return;
                lease.setKeys(task.sneak()?Set.of(mc.gameSettings.keyBindSneak.getKeyCode()):Set.of());phase="settle";
            }
            case "break"->breakStep();
            case "settle"->settle();
            case "expect"->expect();
            default->throw new IllegalStateException("unknown phase "+phase);
        }
    }
    private static String blocked(Map<String,Object> diagnosis) {
        Object first=list(diagnosis.getOrDefault("blocking",List.of())).stream().findFirst().orElse(null);
        return first==null?"":" (blocked by "+object(first).get("id")+" at "+object(first).get("pos")+")";
    }
    @SuppressWarnings("unchecked") private static <T> T result(Future<?> f) {
        try{return (T)f.get();}catch(ExecutionException e){throw new IllegalStateException("click search failed: "+e.getCause(),e.getCause());}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
    }
    private void next() {
        task=null;lease.setKeys(Set.of());
        if(!restores.isEmpty()) {
            Map<String,Object> row=restores.get(0);BlockPos p=pos(row.get("pos"));
            var restoreStep=new StepPlan.Step(-1,"restore "+p.getX()+","+p.getY()+","+p.getZ(),true,p,string(row,"id",""),integer(row,"meta",0,0,15),Map.of(),ClickSpec.ANY,List.of(),false);
            if(!replaceable(p)) {
                boolean same=present(restoreStep);restores.remove(0);
                logAccess(row,same?"already_restored":"occupied_by_other",null);save();return;
            }
            task=new Task("restore",restoreStep,null,row,null);phase="observe";return;
        }
        if(endingWith!=null&&!endingWith[0].isEmpty()){String[] end=endingWith;endingWith=new String[]{"",""};finish(end[0],end[1]);return;}
        for(int index:sequence)if(!receipts.containsKey(index)) {
            StepPlan.Step s=steps.get(index);
            if(s.place()&&present(s)){receipts.put(index,receipt(s,"already_present",null));save();continue;}
            if(s.place()&&!replaceable(s.pos())){finish("failed","occupied: "+s.label()+" holds "+WorkAccess.observed(world,s.pos()).get("id"));return;}
            if(!s.place()&&s.id()!=null&&!s.id().equals(WorkAccess.observed(world,s.pos()).get("id"))){finish("failed","use_target_changed: "+s.label()+" holds "+WorkAccess.observed(world,s.pos()).get("id"));return;}
            task=new Task("step",s,null,null,null);phase="observe";return;
        }
        boolean unverified=receipts.values().stream().anyMatch(r->"unverified".equals(r.get("result")));
        finish("succeeded",unverified?"steps_done_some_unverified":"steps_done");
    }
    /** Pure, off the game thread: vantages, else the problem and (with access) the cells whose removal opens one. */
    private static Searched search(ClickSpace space,Task t,Vantages.Body body,Access access,Set<BlockPos> prot,Set<BlockPos> planned) {
        var target=t.target();var tally=new Vantages.Tally();
        var found=Vantages.search(space,target,body,8,tally).stream().filter(v->!t.tried.contains(v.feet())).toList();
        if(!found.isEmpty())return new Searched(found,null,Map.of(),List.of());
        String problem=Vantages.problem(space,target,tally);
        List<Vantages.Opening> openings=List.of();
        if(access!=null&&!problem.equals("support_missing")&&!problem.equals("hit_not_on_face"))
            openings=Vantages.openings(space,target,body,p->access.refusal(space,p,planned,prot::contains)==null,null,3).stream().filter(o->o.remove().size()<=access.maxCells()).toList();
        return new Searched(List.of(),problem,diagnosis(space,target,tally,problem,t.label()),openings);
    }
    /** What the model sees when a click has no vantage: the clicked cell and face, the cells in the way (most often first), and counts. */
    static Map<String,Object> diagnosis(ClickSpace space,Vantages.Target target,Vantages.Tally tally,String problem,String label) {
        Map<String,Object> out=new LinkedHashMap<>();out.put("step",label);out.put("problem",problem);
        out.put("clicks",target.clicks().stream().map(c->Map.of("block",point(c.block()),"face",ClickSpec.NAMES[c.face()],"present",space.at(c.block()).id())).toList());
        List<Map<String,Object>> blocking=new ArrayList<>();
        tally.occluders.entrySet().stream().sorted(Map.Entry.<BlockPos,Integer>comparingByValue().reversed()).limit(3).forEach(e->{
            var v=space.at(e.getKey());Map<String,Object> row=new LinkedHashMap<>();row.put("pos",point(e.getKey()));row.put("id",v.id());row.put("tile",v.tile());row.put("rays",e.getValue());blocking.add(row);});
        out.put("blocking",blocking);
        out.put("rejected",Map.of("noFooting",tally.stand,"outOfReach",tally.reach,"wrongSideOfFace",tally.side,"lineOfSightBlocked",tally.sight,"lookNotAllowed",tally.look,"bodyInPlacedCell",tally.body));
        return out;
    }
    static Map<String,Object> vantage(Vantages.Vantage v) {
        Map<String,Object> out=new LinkedHashMap<>();out.put("stand",point(v.feet()));out.put("block",point(v.click().block()));out.put("face",ClickSpec.NAMES[v.click().face()]);
        out.put("yaw",Math.round(v.yaw()*10)/10.0);out.put("pitch",Math.round(v.pitch()*10)/10.0);return out;
    }
    private void route() {
        BlockPos feet=WorkAccess.feet();
        if(mc.thePlayer.onGround&&task.vantages.stream().anyMatch(v->v.feet().equals(feet))){phase="select";return;}
        List<BlockPos> goals=task.vantages.stream().map(Vantages.Vantage::feet).distinct().toList();
        lease.setKeys(Set.of());
        nav=new ReferenceNavigationJob(engine,goals,Math.max(1,remaining),allowBreak,allowPlace,override,lease,null,0);phase="moving";
    }
    private void open(Vantages.Opening opening) {
        task.opening=new ArrayDeque<>(opening.remove());task.opened=true;
        Map<String,Object> note=new LinkedHashMap<>();note.put("step",task.label());note.put("stand",point(opening.feet()));note.put("remove",opening.remove().stream().map(WorkSpec::point).toList());
        accessLog.add(note);breakNext();
    }
    private void breakNext() {
        Task parent=task;BlockPos cell=parent.opening.poll();
        if(cell==null){parent.opening=null;parent.tried.clear();phase="observe";return;}
        task=new Task("break",null,cell,null,parent);phase="observe";
    }
    private void select() {
        lease.setKeys(Set.of());
        int slot;
        if(task.breaking()) {
            var choice=MiningTools.choose(world,task.block,null);
            if(!Boolean.TRUE.equals(choice.get("harvestable"))||choice.get("bestSlot")==null){finish("failed","access_cell_not_harvestable: "+task.label()+" ("+WorkAccess.observed(world,task.block).get("id")+")");return;}
            slot=(Integer)choice.get("bestSlot");
        }else if(task.kind.equals("restore"))slot=restoreSlot();
        else if(task.step.place())slot=placeSlot(task.step);
        else if(task.step.item().containsKey("empty")) {
            if(mc.thePlayer.getHeldItem()==null){phase=expectBefore()?"expect_before":"aim";return;}
            int empty=-1;for(int i=0;i<9;i++)if(mc.thePlayer.inventory.mainInventory[i]==null){empty=i;break;}
            if(empty<0){finish("failed","no_empty_hand: "+task.label()+" needs an empty hotbar slot");return;}
            mc.thePlayer.inventory.currentItem=empty;phase=expectBefore()?"expect_before":"aim";return;
        }else slot=itemSlot(task.step.item());
        if(slot<0){finish("failed","missing_item: "+task.label()+" needs "+(task.kind.equals("restore")?task.restore.get("id"):task.step.item().isEmpty()?task.step.id():task.step.item()));return;}
        ItemStack held=mc.thePlayer.getHeldItem();
        if(slot==mc.thePlayer.inventory.currentItem&&held!=null){phase=task.breaking()?"aim":expectBefore()?"expect_before":"aim";return;}
        selection=new InventorySelection(slot);phase="selecting";
    }
    private int placeSlot(StepPlan.Step s){return placeSlot(mc.thePlayer.inventory.mainInventory,s);}
    private int itemSlot(Map<String,Object> selector){return itemSlot(mc.thePlayer.inventory.mainInventory,selector);}
    /** The inventory slot a place step takes its block from: the step's item selector, else a stack of its block (that meta first), or -1. */
    static int placeSlot(ItemStack[] inventory,StepPlan.Step s) {
        if(!s.item().isEmpty())return itemSlot(inventory,s.item());
        int any=-1;
        for(int i=0;i<36&&i<inventory.length;i++) {
            ItemStack st=inventory[i];
            if(st==null||st.stackSize<=0||!(st.getItem() instanceof ItemBlock)||!s.id().equals(Registry.name(Block.getBlockFromItem(st.getItem()))))continue;
            if(s.meta()==null||st.getItem().getMetadata(st.getItemDamage())==s.meta())return i;
            if(any<0)any=i; // the meta may come from the click (a facing); the check after placing says
        }
        return any;
    }
    static int itemSlot(ItemStack[] inventory,Map<String,Object> selector){for(int i=0;i<36&&i<inventory.length;i++)if(WorkAccess.item(inventory[i],selector))return i;return -1;}
    private int restoreSlot() {
        int exact=placeSlot(task.step);task.substitute=null;
        if(exact>=0&&mc.thePlayer.inventory.mainInventory[exact].getItem().getMetadata(mc.thePlayer.inventory.mainInventory[exact].getItemDamage())==task.step.meta())return exact;
        // Next to a tile entity only the same block goes back (its metadata is checked after); no substitute.
        if(Boolean.TRUE.equals(task.restore.get("near")))return exact;
        if(exact>=0){task.substitute=Map.of("id",task.step.id(),"note","same block, other variant");return exact;}
        for(int i=0;i<36;i++) {
            ItemStack st=mc.thePlayer.inventory.mainInventory[i];
            if(st==null||!(st.getItem() instanceof ItemBlock))continue;
            Block b=Block.getBlockFromItem(st.getItem());
            if(b.isOpaqueCube()&&b.renderAsNormalBlock()&&!b.hasTileEntity(st.getItem().getMetadata(st.getItemDamage()))){task.substitute=InventorySelection.describe(st);return i;}
        }
        return -1;
    }
    private boolean expectBefore() {
        if(task.step==null||task.step.expect().stream().noneMatch(e->e.op().equals("changed"))||task.expectBefore!=null)return false;
        task.reply=read(task.step.expect());task.wait=0;return task.reply!=null;
    }
    private AtomicReference<Map<String,Object>> read(List<Expectation> expect) {
        Observations o=Observations.Registry.get();if(o==null)return null;
        Map<String,Map<String,Object>> queries=new LinkedHashMap<>();
        for(int i=0;i<expect.size();i++)queries.put("e"+i,Map.of("method",expect.get(i).method(),"params",expect.get(i).request()));
        AtomicReference<Map<String,Object>> out=new AtomicReference<>();o.read(queries,out::set);return out;
    }
    private void aim() {
        BlockPos feet=WorkAccess.feet();
        Vantages.Vantage v=task.vantages.stream().filter(x->x.feet().equals(feet)).findFirst().orElse(null);
        if(v==null){if(task.vantages.isEmpty()){phase="observe";return;}v=task.vantages.get(0);}
        task.at=v;
        var eye=mc.thePlayer.getPosition(1);
        double dx=v.point().x()-eye.xCoord,dy=v.point().y()-eye.yCoord,dz=v.point().z()-eye.zCoord;
        float yaw=(float)Math.toDegrees(Math.atan2(-dx,dz)),pitch=(float)-Math.toDegrees(Math.atan2(dy,Math.hypot(dx,dz)));
        Set<Integer> keys=task.sneak()?Set.of(mc.gameSettings.keyBindSneak.getKeyCode()):Set.of();
        lease.look(yaw,pitch);lease.setKeys(keys);
        ClickSpec.Look look=task.breaking()?ClickSpec.Look.ANY:task.step.click().look();
        boolean ready=look.accepts(mc.thePlayer.rotationYaw,mc.thePlayer.rotationPitch)&&mc.thePlayer.isSneaking()==task.sneak()
            &&Math.hypot(mc.thePlayer.motionX,mc.thePlayer.motionZ)<.02&&mc.thePlayer.onGround;
        dev.modbench.api.ControlRegistry.targeting().refresh();var hit=mc.objectMouseOver;
        boolean on=hit!=null&&hit.typeOfHit==MovingObjectPosition.MovingObjectType.BLOCK&&hit.blockX==v.click().block().getX()&&hit.blockY==v.click().block().getY()&&hit.blockZ==v.click().block().getZ()
            &&(task.breaking()||hit.sideHit==v.click().face())
            &&(task.breaking()||task.step.click().hit()==null||Math.abs(hit.hitVec.xCoord-v.point().x())+Math.abs(hit.hitVec.yCoord-v.point().y())+Math.abs(hit.hitVec.zCoord-v.point().z())<.06);
        if(on&&ready&&!baritone.compat.RecentPlacements.recent(v.click().block())) {
            task.clicked=new LinkedHashMap<>();task.clicked.put("block",point(v.click().block()));task.clicked.put("face",ClickSpec.NAMES[hit.sideHit]);
            task.clicked.put("hit",List.of(round(hit.hitVec.xCoord-hit.blockX),round(hit.hitVec.yCoord-hit.blockY),round(hit.hitVec.zCoord-hit.blockZ)));
            task.clicked.put("yaw",round(mc.thePlayer.rotationYaw));task.clicked.put("pitch",round(mc.thePlayer.rotationPitch));task.clicked.put("sneak",task.sneak());task.clicked.put("stand",point(feet));
            task.before=state(task.pos());
            if(task.breaking()) {
                // Journaled before the first hit, so a crash or a resume puts it back.
                Map<String,Object> row=new LinkedHashMap<>();row.put("pos",point(task.block));row.put("id",task.before.get("id"));row.put("meta",task.before.get("meta"));
                row.put("near",!access.substituteAllowed(task.parent.space,task.block));row.put("removedAt",world.getTotalWorldTime());row.put("for",task.parent.label());
                task.restore=row;restores.add(row);save();
                phase="break";task.hold=0;task.settle=0;lease.setKeys(Set.of());return;
            }
            if(task.step.place()) {
                var predicted=baritone.compat.LegacyPlacement.predict(engine.getPlayerContext(),mc.thePlayer.getHeldItem(),baritone.compat.RayTraceResult.fromNative(hit),new baritone.api.utils.Rotation(mc.thePlayer.rotationYaw,mc.thePlayer.rotationPitch));
                task.predicted=Map.of("id",String.valueOf(Registry.name(predicted.getBlock())),"meta",predicted.meta);
            }
            Set<Integer> press=new HashSet<>(keys);press.add(mc.gameSettings.keyBindUseItem.getKeyCode());lease.setKeys(press);
            task.settle=0;task.pressTick=ticks;phase="click";return;
        }
        if(++task.aim>30) {
            // This stance does not give the planned view (the body is off-centre, a mob is in the way): try the others.
            task.tried.add(v.feet());task.aim=0;
            task.vantages=task.vantages.stream().filter(x->!task.tried.contains(x.feet())).toList();
            if(task.vantages.isEmpty()){
                diagnosis=Map.of("step",task.label(),"aimedAt",vantage(v),"nativeHit",hit==null?"none":hit.typeOfHit==MovingObjectPosition.MovingObjectType.BLOCK?Map.of("pos",List.of(hit.blockX,hit.blockY,hit.blockZ),"face",ClickSpec.NAMES[hit.sideHit]):hit.typeOfHit.name());
                if(task.tried.size()<16){phase="observe";return;}
                finish("failed","aim_mismatch: "+task.label());return;
            }
            route();
        }
    }
    private static double round(double v){return Math.round(v*1000)/1000.0;}
    private void breakStep() {
        if(++task.hold>400){finish("failed","access_break_timeout: "+task.label());return;}
        if(task.hold==1){lease.setKeys(Set.of());return;} // a released tick first: focus changes suppress the next attack
        if(replaceable(task.block)){lease.setKeys(Set.of());if(++task.settle>=4){finishBreak();}return;}
        dev.modbench.api.ControlRegistry.targeting().refresh();var hit=mc.objectMouseOver;
        boolean on=hit!=null&&hit.typeOfHit==MovingObjectPosition.MovingObjectType.BLOCK&&hit.blockX==task.block.getX()&&hit.blockY==task.block.getY()&&hit.blockZ==task.block.getZ();
        lease.setKeys(on?Set.of(mc.gameSettings.keyBindAttack.getKeyCode()):Set.of());
        if(!on&&task.hold>40){mc.playerController.resetBlockRemoving();phase="aim";task.aim=0;}
    }
    private void finishBreak() {
        Task parent=task.parent;
        logAccess(task.restore==null?null:task.restore,"removed",null);
        task=parent;breakNext();save();
    }
    private void settle() {
        task.settle++;
        if(task.step.place()) {
            if(replaceable(task.pos())){if(task.settle>20)finish("failed","server_rejected_placement: "+task.label());return;}
            if(task.settle<2)return;
            baritone.compat.RecentPlacements.mark(task.pos());
        }else if(task.settle<4)return;
        if(!task.step.expect().isEmpty()){task.reply=null;task.wait=-1;task.expectTries=0;phase="expect";return;}
        record(null,null);
    }
    private void expect() {
        if(task.reply==null) {
            if(++task.wait<0)return;
            task.reply=read(task.step.expect());task.wait=0;
            if(task.reply==null)record("unverified","observations_unavailable");
            return;
        }
        var reply=task.reply.get();
        if(reply==null){if(++task.wait>200)finish("failed","expectation_read_timeout: "+task.label());return;}
        List<Map<String,Object>> observed=new ArrayList<>();boolean all=true;
        var values=child(reply,"values");var before=task.expectBefore==null?Map.<String,Object>of():child(task.expectBefore,"values");
        for(int i=0;i<task.step.expect().size();i++) {
            Expectation e=task.step.expect().get(i);
            Object value=values.containsKey("e"+i)?Expectation.extract(values.get("e"+i),e.path()):Expectation.MISSING;
            Object prior=before.containsKey("e"+i)?Expectation.extract(before.get("e"+i),e.path()):Expectation.MISSING;
            boolean met=e.met(prior,value);all&=met;
            Map<String,Object> row=new LinkedHashMap<>(e.json(origin));row.put("observed",value==Expectation.MISSING?"missing":value);
            if(e.op().equals("changed"))row.put("before",prior==Expectation.MISSING?"missing":prior);
            if(child(reply,"errors").containsKey("e"+i))row.put("error",child(reply,"errors").get("e"+i));
            if(reply.containsKey("error"))row.put("error",reply.get("error"));
            row.put("met",met);observed.add(row);
        }
        task.observed=observed;
        if(all){record("verified",null);return;}
        if(task.expectTries++==0){task.reply=null;task.wait=-20;return;} // read once more, 20 ticks later; the step is not clicked again
        diagnosis=Map.of("step",task.label(),"expect",observed);
        failedStep=receipt(task.step,"expect_failed",task);save();
        finish("failed","expect_failed: "+task.label());
    }
    private void record(String result,String note) {
        String verdict=result;
        if(task.kind.equals("restore")) {
            restores.remove(task.restore);var after=state(task.pos());
            logAccess(task.restore,Objects.equals(after.get("id"),task.step.id())&&Objects.equals(after.get("meta"),task.step.meta())?"restored":task.substitute!=null?"substituted":"restored_differently",after);
            save();phase="next";return;
        }
        if(verdict==null) {
            if(!task.step.place())verdict="used";
            else {
                var after=state(task.pos());
                boolean id=task.step.id().equals(after.get("id"));
                Object wantMeta=task.step.meta()!=null?task.step.meta():task.predicted==null?null:task.predicted.get("meta");
                boolean meta=wantMeta==null||Objects.equals(((Number)wantMeta).intValue(),after.get("meta"));
                if(!id||!meta){failedStep=receipt(task.step,"mismatch",task);save();finish("failed","placed_state_differs: "+task.label()+" is "+after.get("id")+":"+after.get("meta"));return;}
                // A tile entity can keep a facing or side outside block and metadata, so these do not confirm what the click set.
                verdict=Boolean.TRUE.equals(after.get("hasTile"))&&task.step.click().orients()?"unverified":"verified";
            }
        }
        var row=receipt(task.step,verdict,task);if(note!=null)row.put("note",note);
        receipts.put(task.step.index(),row);save();phase="next";
    }
    private Map<String,Object> receipt(StepPlan.Step s,String result,Task t) {
        Map<String,Object> out=new LinkedHashMap<>();out.put("step",s.index());out.put("label",s.label());out.put("kind",s.place()?"place":"use");out.put("result",result);
        if(t!=null) {
            if(t.clicked!=null)out.put("clicked",t.clicked);
            Map<String,Object> cell=new LinkedHashMap<>();cell.put("pos",point(s.pos()));cell.put("before",t.before);cell.put("after",state(s.pos()));
            out.put(s.place()?"placed":"target",cell);
            if(t.predicted!=null)out.put("predicted",t.predicted);
            if(t.observed!=null)out.put("expect",t.observed);
            if("unverified".equals(result))out.put("why","the block has a tile entity, which can keep a facing or side outside block and metadata; an expect on this step can confirm it");
        }
        return out;
    }
    private Map<String,Object> state(BlockPos p) {
        Map<String,Object> out=new LinkedHashMap<>();Block b=world.getBlock(p.getX(),p.getY(),p.getZ());int meta=world.getBlockMetadata(p.getX(),p.getY(),p.getZ());
        out.put("id",b.isAir(world,p.getX(),p.getY(),p.getZ())?"minecraft:air":String.valueOf(Registry.name(b)));out.put("meta",meta);out.put("hasTile",b.hasTileEntity(meta));return out;
    }
    private boolean replaceable(BlockPos p){Block b=world.getBlock(p.getX(),p.getY(),p.getZ());return b.isAir(world,p.getX(),p.getY(),p.getZ())||b.isReplaceable(world,p.getX(),p.getY(),p.getZ());}
    private boolean present(StepPlan.Step s) {
        var now=state(s.pos());return s.id().equals(now.get("id"))&&(s.meta()==null||s.meta().equals(now.get("meta")));
    }
    private void logAccess(Map<String,Object> row,String what,Map<String,Object> after) {
        Map<String,Object> out=new LinkedHashMap<>();if(row!=null)out.putAll(row);out.put("event",what);out.put("tick",ticks);
        if(after!=null)out.put("after",after);
        if(task!=null&&task.substitute!=null&&what.equals("substituted"))out.put("substitute",task.substitute);
        if(row!=null&&row.get("removedAt") instanceof Number at&&!what.equals("removed")) {
            long open=world.getTotalWorldTime()-at.longValue();out.put("ticksOpen",open);
            if(Boolean.TRUE.equals(row.get("near"))&&open>Access.NEAR_TILE_RESTORE_TICKS)out.put("late",true);
        }
        accessLog.add(out);if(accessLog.size()>64)accessLog.remove(0);
    }
    private void save() {
        Map<String,Object> saved=new LinkedHashMap<>();receipts.forEach((k,v)->saved.put(String.valueOf(k),v));
        journal.progress.put("steps",saved);journal.progress.put("restore",List.copyOf(restores));journal.progress.put("accessLog",List.copyOf(accessLog));
        journal.save(status());
    }
    // The ending of a job with cells still removed for access: put them back first, unless the model or a death ended it.
    @Override boolean ending(String terminal,String why) {
        if(endingWith!=null) {
            if(endingWith[0].isEmpty())return false;
            // Putting the cells back failed: end as the job was ending, and say why the cells are still out.
            String[] end=endingWith;endingWith=new String[]{"",""};finish(end[0],end[1]+"; restore_failed: "+why);return true;
        }
        if(restores.isEmpty()||terminal.equals("cancelled")||why.equals("player_died")||mc.thePlayer==null||mc.thePlayer!=player)return false;
        endingWith=new String[]{terminal,why};
        if(nav!=null&&!nav.done())nav.cancel("restoring_access");nav=null;
        if(selection!=null){selection.close();selection=null;}
        future=null;capture=null;task=null;remaining=Math.max(remaining,1200+600*restores.size());phase="next";return true;
    }
    @Override void releaseProcess() {
        if(nav!=null&&!nav.done())nav.cancel("job_ended");
        if(selection!=null)selection.close();
        engine.getPathingBehavior().forceCancel();engine.getInputOverrideHandler().release();
        save();
    }
    /**
     * The read-only answer to whether these steps can be made from here: the order, and per step a vantage or the problem
     * with the cells in the way (and, with access allowed, the cells that would open one), in the world as it would be
     * after the steps before it. A plan-time call: it copies and searches on the game thread in one go.
     */
    static Map<String,Object> preview(Map<String,Object> spec) {
        var mc=net.minecraft.client.Minecraft.getMinecraft();var world=mc.theWorld;
        List<StepPlan.Step> steps=StepPlan.parse(spec);boolean override=bool(spec,"overrideProtection",false);
        Access access=spec.containsKey("access")?Access.parse(child(spec,"access")):Access.OFF;
        BlockPos origin=spec.containsKey("origin")?pos(spec.get("origin")):new BlockPos(0,0,0);
        Map<String,Object> out=new LinkedHashMap<>();out.put("kind","steps");
        List<String> unloaded=new ArrayList<>(),protectedSteps=new ArrayList<>();
        for(var s:steps){
            if(!ForgeSnapshot.loaded(world,s.pos().getX(),s.pos().getY(),s.pos().getZ()))unloaded.add(s.label());
            else if(s.place()&&WorkAccess.protection(s.pos(),override)!=null)protectedSteps.add(s.label());
        }
        out.put("unloaded",unloaded);out.put("protected",protectedSteps);
        if(!unloaded.isEmpty()){out.put("ready",false);return out;}
        ClickWorld copy=ClickWorld.around(world,steps.stream().map(StepPlan.Step::pos).toList(),(int)Math.ceil(mc.playerController.getBlockReachDistance())+2,override);
        while(!copy.step(Long.MAX_VALUE)){}
        ClickSpace w=copy.space();Set<BlockPos> prot=copy.protectedCells;Set<BlockPos> planned=new HashSet<>();
        steps.stream().filter(StepPlan.Step::place).forEach(s->planned.add(s.pos()));
        List<StepPlan.Step> open=new ArrayList<>();List<String> present=new ArrayList<>();
        for(var s:steps){var v=w.at(s.pos());if(s.place()&&s.id().equals(v.id())&&(s.meta()==null||s.meta().equals(world.getBlockMetadata(s.pos().getX(),s.pos().getY(),s.pos().getZ()))))present.add(s.label());else open.add(s);}
        var order=StepPlan.order(open,w,ClickWorld.body(mc,true),600);
        List<Map<String,Object>> rows=new ArrayList<>();boolean ready=protectedSteps.isEmpty();
        for(var s:order.steps()) {
            Map<String,Object> row=new LinkedHashMap<>();row.put("step",s.index());row.put("label",s.label());row.put("kind",s.place()?"place":"use");
            row.put("pos",List.of(s.pos().getX()-origin.getX(),s.pos().getY()-origin.getY(),s.pos().getZ()-origin.getZ()));
            boolean sneak=s.click().sneak()!=null?s.click().sneak():s.place();row.put("sneak",sneak);
            if(s.place()&&!w.at(s.pos()).replaceable()){row.put("ready",false);row.put("problem","occupied");row.put("present",w.at(s.pos()).id());ready=false;}
            else {
                var tally=new Vantages.Tally();var body=ClickWorld.body(mc,sneak);
                var found=Vantages.search(w,s.target(),body,1,tally);
                if(!found.isEmpty()){row.put("ready",true);row.put("vantage",vantage(found.get(0)));}
                else {
                    String problem=Vantages.problem(w,s.target(),tally);ready=false;
                    row.put("ready",false);row.put("problem",problem);row.put("diagnosis",diagnosis(w,s.target(),tally,problem,s.label()));
                    if(access.allowed()) {
                        ClickSpace space=w;
                        var openings=Vantages.openings(space,s.target(),body,p->access.refusal(space,p,planned,prot::contains)==null,null,3).stream().filter(o->o.remove().size()<=access.maxCells()).toList();
                        row.put("openings",openings.stream().map(o->Map.of("stand",point(o.feet()),"remove",o.remove().stream().map(p->Map.of("pos",point(p),"id",space.at(p).id(),"identicalRestore",!access.substituteAllowed(space,p))).toList())).toList());
                    }
                }
            }
            int slot=s.place()?placeSlot(mc.thePlayer.inventory.mainInventory,s):s.item().containsKey("empty")?0:itemSlot(mc.thePlayer.inventory.mainInventory,s.item());
            row.put("itemInInventory",slot>=0);if(slot<0)ready=false;
            if(s.place())w=w.with(s.pos(),ClickSpace.Voxel.full(s.pos(),s.id(),false));
            rows.add(row);
        }
        out.put("order",order.json());out.put("steps",rows);out.put("alreadyPresent",present);out.put("ready",ready&&order.conflicts().isEmpty());
        out.put("meaning","ready: every step has a vantage in the world as the steps before it leave it, and its item is in the inventory; vantage is one stance that works (the job may use another); the game ray decides at the click");
        return out;
    }
    /**
     * For a cell plan's preview: each cell that fixes a placement face or hit (up to 32), whether some stance gives that
     * click in the world as it is and in the world with every other plan cell placed, the case the build's fail-fast judges.
     */
    static Map<String,Object> cellChecks(List<WorkSpec.Cell> cells,boolean override) {
        var mc=net.minecraft.client.Minecraft.getMinecraft();var world=mc.theWorld;
        List<WorkSpec.Cell> fixed=cells.stream().filter(c->!c.clear()&&(c.placement().containsKey("face")||c.placement().containsKey("hit"))).limit(32).toList();
        Map<String,Object> out=new LinkedHashMap<>();
        if(fixed.isEmpty()){out.put("cells",List.of());return out;}
        ClickWorld copy;
        try{copy=ClickWorld.around(world,fixed.stream().map(WorkSpec.Cell::pos).toList(),(int)Math.ceil(mc.playerController.getBlockReachDistance())+2,override);}
        catch(IllegalArgumentException spread){out.put("skipped",spread.getMessage());return out;}
        while(!copy.step(Long.MAX_VALUE)){}
        ClickSpace now=copy.space();Map<BlockPos,ClickSpace.Voxel> placed=new HashMap<>();
        for(var c:cells)if(!c.clear()&&now.inside(c.pos()))placed.put(c.pos(),ClickSpace.Voxel.full(c.pos(),c.id(),false));
        ClickSpace full=now.with(placed);
        var body=ClickWorld.body(mc,true);List<Map<String,Object>> rows=new ArrayList<>();
        for(var c:fixed) {
            Integer face=c.placement().containsKey("face")?((Number)c.placement().get("face")).intValue():null;
            ClickSpec.Vec hit=null;
            if(c.placement().get("hit") instanceof List<?> h&&h.size()==3)hit=new ClickSpec.Vec(((Number)h.get(0)).doubleValue(),((Number)h.get(1)).doubleValue(),((Number)h.get(2)).doubleValue());
            var target=new Vantages.Target(Vantages.placing(c.pos(),face),hit,ClickSpec.Look.ANY,c.pos());
            String label="cell "+c.pos().getX()+","+c.pos().getY()+","+c.pos().getZ();
            Map<String,Object> row=new LinkedHashMap<>();row.put("pos",point(c.pos()));
            ClickSpace without=full.with(c.pos(),now.at(c.pos()));
            for(var e:Map.of("now",now,"restPlaced",without).entrySet()) {
                var tally=new Vantages.Tally();var found=Vantages.search(e.getValue(),target,body,1,tally);
                row.put(e.getKey(),found.isEmpty()?diagnosis(e.getValue(),target,tally,Vantages.problem(e.getValue(),target,tally),label):Map.of("vantage",vantage(found.get(0))));
            }
            rows.add(row);
        }
        out.put("cells",rows);
        out.put("meaning","now: the click from the world as it is; restPlaced: with every other plan cell placed as a full block. A problem in restPlaced, in a job that may not break or place outside the plan, is where the build stops with that reason; the game ray decides at the click");
        return out;
    }
    @Override public Map<String,Object> status() {
        var out=super.status();out.put("process","ClickSteps");out.put("phase",phase);out.put("steps",steps.size());out.put("done",receipts.size());
        out.put("current",task==null?null:task.label());out.put("order",orderReport);
        List<Map<String,Object>> list=new ArrayList<>(receipts.values());out.put("receipts",list.size()>64?list.subList(list.size()-64,list.size()):list);
        out.put("restorePending",List.copyOf(restores));out.put("access",List.copyOf(accessLog));
        if(!diagnosis.isEmpty())out.put("diagnosis",diagnosis);
        if(!failedStep.isEmpty())out.put("failedStep",failedStep);
        out.put("resultMeaning","verified: block, metadata (given or predicted from the click) and expectations agree; unverified: the block has a tile entity and the step fixes a face, hit or look, which block and metadata cannot confirm; used: a use step without expectations; already_present: the cell held the block before the job");
        return out;
    }
}
