// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh;

import baritone.Baritone;
import baritone.compat.BlockPos;
import baritone.gtnh.pathing.*;
import static baritone.gtnh.pathing.WorkSpec.*;
import java.util.*;

/** Journal, native selectors and action ownership around the actual upstream MineProcess. */
final class MiningProcess extends BulkJob {
    private final List<Map<String,Object>> items;
    // With no items named, any gain counts: the inventory at this session's start, per identity (MiningProcess.identity).
    private final Map<String,Integer> start;
    private final int quantity,baseline,gainedBefore;
    private final Bounds bounds;
    private final Baritone engine;
    private final MiningObservation observation;
    private final Map<baritone.api.Settings.Setting<?>,Object> scopedSettings=new LinkedHashMap<>();
    private boolean started;
    private Integer finalCount;
    private String finalGoal;
    private int inactiveTicks,pathlessTicks,rejectedSeen,rejections,haveAtReject=-1;
    private record DropLocation(int entityId,baritone.compat.BlockPos position){}
    private final Set<DropLocation> retriedDrops=new HashSet<>();
    private List<Map<String,Object>> diagnostics=List.of();
    private List<List<Integer>> lastKnown=List.of(),lastRejected=List.of();
    // Targets the engine dropped, and why. Unreachable ones are journaled: a resume does not walk back to them unless it
    // passes retry:true; every other reason is the world's and the inventory's, and is judged again as they change.
    private final Set<BlockPos> unreachable=new LinkedHashSet<>();
    private Map<BlockPos,String> skippedNow=Map.of();
    private List<Map<String,Object>> skipped=List.of();
    private final boolean besideFluid;
    private final String toolSlotTool;
    private BlockPos breaking;
    private final Set<BlockPos> plugged=new HashSet<>();
    private int unplugged;
    // What each swing broke besides the block it was aimed at. The job knows no tool by name, so a 3x3 hammer or a vein
    // miner shows up here as a measurement, and a swing that took a protected block ends the job. A swing is one unbroken
    // hold of the attack on one block with one tool; start and limit time it against the game's own break estimate.
    private record Swing(BlockPos target,net.minecraft.block.Block block,Map<BlockPos,net.minecraft.block.Block> around,int due,int start,String tool,int limit){}
    private final List<Map<String,Object>> ineffective=new ArrayList<>();
    private Swing swing;
    private final List<Swing> settling=new ArrayList<>();
    private final Set<BlockPos> aimed=new HashSet<>();
    private int broken,extraBroken;
    private final List<List<Integer>> extraAt=new ArrayList<>();
    private Integer dropsLeft;
    MiningProcess(BaritoneNavigation nav,WorkJournal journal,Map<String,Object> options){
        super(nav,journal,options);engine=nav.reference();
        var blocks=WorkAccess.selectors(params.get("blocks"));items=params.containsKey("items")?WorkAccess.itemSelectors(params.get("items")):List.of();
        start=items.isEmpty()?stacks():null;
        quantity=integer(params,"quantity",1,1,1000000);
        besideFluid=bool(params,"besideFluid",false);
        // toolSlot forces the tool in that slot now, wherever a swap later moves it; what it breaks is still measured.
        if(params.containsKey("toolSlot")){var stack=mc.thePlayer.inventory.getStackInSlot(integer(params,"toolSlot",0,0,35));
            if(stack==null)throw new IllegalArgumentException("toolSlot is empty");toolSlotTool=MiningTools.toolKind(stack);}
        else toolSlotTool=null;
        if(besideFluid&&!allowPlace)throw new IllegalArgumentException("besideFluid plugs the holes it opens: it needs allowPlace and a throwaway block (cobblestone, dirt) in the hotbar");
        BlockPos origin=WorkAccess.feet();int radius=integer(params,"radius",24,1,64);
        Map<String,Object> scan=params.containsKey("bounds")?child(params,"bounds"):Map.of("min",List.of(origin.getX()-radius,Math.max(1,origin.getY()-16),origin.getZ()-radius),"max",List.of(origin.getX()+radius,Math.min(254,origin.getY()+16),origin.getZ()+radius));
        bounds=bounds(scan);if(bounds.volume()>262144)throw new IllegalArgumentException("mining scan exceeds 262144 cells");
        journal.spec.put("bounds",scan);
        // Gain is summed over sessions, each measured from its own start, so ore smelted or stored between a pause and
        // the resume still counts and ore fetched from a chest meanwhile does not.
        int now=have(),legacy=journal.progress.containsKey("initialCount")?Math.max(0,now-integer(journal.progress,"initialCount",now,0,1000000)):0;
        gainedBefore=integer(journal.progress,"gained",legacy,0,1000000);baseline=now-gainedBefore;
        observation=new MiningObservation(world,bounds,blocks,items);
        if(!bool(options,"retry",false))for(Object p:list(journal.progress.getOrDefault("unreachable",List.of())))unreachable.add(pos(p));
        rejectedSeen=unreachable.size();
    }
    @Override void begin(){
        super.begin();engine.getPathingBehavior().forceCancel();
        for(var setting:List.of(Baritone.settings().allowBreak,Baritone.settings().allowPlace,Baritone.settings().exploreForBlocks,Baritone.settings().legitMine,Baritone.settings().allowInventory))scopedSettings.put(setting,setting.value);
        Baritone.settings().allowInventory.value=true; // the best tool anywhere in the inventory, not only the hotbar
        MiningTools.ineffective.clear();BlockRules.reset();ReferenceToolPolicy.forcedTool=toolSlotTool;
        Baritone.settings().allowBreak.value=allowBreak;Baritone.settings().allowPlace.value=allowPlace;
        // This action has explicit observation bounds. Exploration is a separate
        // process, not permission to start a branch mine when its bounds empty.
        Baritone.settings().exploreForBlocks.value=false;Baritone.settings().legitMine.value=false;
        engine.overrideProtection=override;engine.positionAllowed=p->true;
        engine.explicitMiningTargets=observation::capture;
        Baritone.besideFluid=besideFluid;
        // A plug is never walked back through: the search may not stand in one, nor under one.
        if(besideFluid)engine.positionAllowed=p->!plugged.contains(p)&&!plugged.contains(new BlockPos(p.getX(),p.getY()+1,p.getZ()));
        engine.getInputOverrideHandler().attach(lease);
    }
    int gained(){return Math.max(0,have()-baseline);}
    /** The named items held, or with none named the sum of each identity's gain since this session started. */
    private int have(){
        if(start==null)return WorkAccess.count(items);
        int gain=0;for(var e:stacks().entrySet())gain+=Math.max(0,e.getValue()-start.getOrDefault(e.getKey(),0));return gain;
    }
    /** An item's identity is its id, meta and NBT; a stack of one (a tool, whose wear some mods keep in NBT) is its id and meta. */
    static String identity(net.minecraft.item.ItemStack s){
        return baritone.compat.Registry.name(s.getItem())+":"+s.getItemDamage()+(s.getMaxStackSize()>1&&s.hasTagCompound()?s.getTagCompound().toString():"");
    }
    private static Map<String,Integer> stacks(){
        Map<String,Integer> out=new HashMap<>();
        for(var s:WorkAccess.MC.thePlayer.inventory.mainInventory)if(s!=null&&s.stackSize>0)out.merge(identity(s),s.stackSize,Integer::sum);
        return out;
    }
    @Override int progress(){return mc.thePlayer==player?gained():progressSeen;}
    // Digging toward a target is work before any ore arrives, and so is the first scan of the bounds, which stands still.
    @Override long activity(){return progress()+(long)broken+(observation.passes==0?observation.cursor:0);}
    @Override String phase(){return "reference_mine";}
    @Override void step(){
        if(gained()>=quantity){finish("succeeded","requested_inventory_gain_observed");return;}
        if(!WorkAccess.room(items)){finish("failed","inventory_full");return;}
        observation.tick();
        if(!started){
            state="scanning";if(observation.passes==0)return;
            // Give the original inventory scheduler its ticks before the initial
            // MineProcess rescan prunes ores using the current hotbar's harvest capability.
            engine.tickStart();
            if(engine.getInventoryBehavior().hasPendingMove()){state="preparing_inventory";return;}
            // The public action counts net gains across resume; the upstream
            // process continues until this wrapper's inventory condition fires.
            engine.bsi=new baritone.utils.BlockStateInterface(engine.getPlayerContext());
            var costs=new baritone.pathing.movement.CalculationContext(engine);
            diagnostics=observation.observedLocations().stream().limit(16).map(p->{
                var s=costs.get(p);double duration=baritone.pathing.movement.MovementHelper.getMiningDurationTicks(costs,p.getX(),p.getY(),p.getZ(),true);
                return Map.<String,Object>of("pos",point(p),"matches",observation.has(s),"canHarvest",costs.toolSet.canHarvest(s),"bestSlot",costs.toolSet.getBestSlot(s),"miningCost",duration,
                    "breakSafetyBlocked",baritone.pathing.movement.MovementHelper.avoidBreaking(costs.bsi,p.getX(),p.getY(),p.getZ(),s),
                    "above",costs.get(p.getX(),p.getY()+1,p.getZ()).toString());
            }).toList();
            engine.getMineProcess().mine(0,observation);engine.getMineProcess().avoid(unreachable);started=true;
            return;
        }
        var process=engine.getMineProcess();
        if(!process.isActive()){
            // A bounded, net-gain action must settle already-issued edits before
            // interpreting the upstream process stopping as a failed receipt.
            // Newly arriving drops still use MineProcess's own collection goals.
            if(inactiveTicks++==0){engine.getPathingBehavior().forceCancel();engine.getInputOverrideHandler().clearAllKeys();engine.getInputOverrideHandler().flush();}
            boolean newDrop=false;
            for(Object entity:world.loadedEntityList)if(entity instanceof net.minecraft.entity.item.EntityItem drop&&!drop.isDead
                    &&observation.has(drop.getEntityItem())
                    &&observation.acceptsDrop(new baritone.compat.BlockPos(drop.posX,drop.boundingBox.minY,drop.posZ))
                    &&retriedDrops.add(new DropLocation(drop.getEntityId(),new baritone.compat.BlockPos(drop.posX,drop.boundingBox.minY,drop.posZ))))newDrop=true;
            if(newDrop){engine.bsi=new baritone.utils.BlockStateInterface(engine.getPlayerContext());process.mine(0,observation);process.avoid(unreachable);}
            if(!process.isActive()){
                state="awaiting_inventory";
                if(inactiveTicks>Math.max(20,(Baritone.settings().mineDropLoiterDurationMSThanksLouca.value+49)/50))finish("failed","no_remaining_reachable_targets_or_drops");
                return;
            }
        }
        inactiveTicks=0;
        if(besideFluid&&plug())return; // this tick belongs to the plug
        engine.tickStart();
        if(besideFluid)watch();
        if(measure())return;
        if(process.isActive()){
            lastKnown=process.knownLocations().stream().map(MiningProcess::point).toList();
            lastRejected=process.rejectedLocations().stream().map(MiningProcess::point).toList();
            unreachable.addAll(process.rejectedLocations());skippedNow=process.skippedLocations();
        }
        // A failed search blacklists ONE target and plans again, seconds apiece. Four in a row with nothing gained between
        // them is a deposit this player cannot reach: end with the reason instead of working through every block of it.
        if(lastRejected.size()>rejectedSeen){
            rejectedSeen=lastRejected.size();int have=have();
            rejections=have==haveAtReject?rejections+1:1;haveAtReject=have;
            if(rejections>=4){finish("failed","no_path_to_targets");return;}
        }
        state=engine.getInputOverrideHandler().isInputForcedDown(baritone.api.utils.input.Input.CLICK_LEFT)?"mining":"pathing";
        // MineProcess keeps its goal while every remaining target is one it may not break (beside still water, say) or
        // cannot reach, and the player would stand there until the timeout. Five seconds with no path and none being
        // planned is that case: end with the reason instead.
        boolean pathless=state.equals("pathing")&&engine.getPathingBehavior().getCurrent()==null&&!engine.getPathingBehavior().getInProgress().isPresent();
        pathlessTicks=pathless?pathlessTicks+1:0;
        if(pathlessTicks>100)finish("failed","no_path_to_remaining_targets");
    }
    @Override void releaseProcess(){
        finalCount=mc.thePlayer==player?have():null;
        if(finalCount!=null)journal.progress.put("gained",Math.max(0,finalCount-baseline));
        if(mc.thePlayer==player&&observation!=null){int left=0;
            for(Object entity:world.loadedEntityList)if(entity instanceof net.minecraft.entity.item.EntityItem drop&&!drop.isDead&&observation.has(drop.getEntityItem())
                    &&bounds.contains(new BlockPos(drop.posX,drop.boundingBox.minY,drop.posZ)))left+=drop.getEntityItem().stackSize;
            dropsLeft=left;}
        finalGoal=String.valueOf(engine.getPathingBehavior().getGoal());
        engine.getPathingBehavior().forceCancel();engine.getInputOverrideHandler().release();
        engine.explicitMiningTargets=()->s->false;Baritone.besideFluid=false;MiningTools.ineffective.clear();ReferenceToolPolicy.forcedTool=null;
        skipped=skipped();journal.progress.put("unreachable",unreachable.stream().limit(256).map(MiningProcess::point).toList());
        scopedSettings.forEach(ReferenceSettings::copy);
    }
    @Override public Map<String,Object> status(){
        Map<String,Object> out=super.status();
        out.put("engine","baritone-1.2.19-source-port");out.put("process","MineProcess");
        out.put("quantity",quantity);out.put("gainedBefore",gainedBefore);
        Integer count=done()?finalCount:mc.thePlayer==player?have():null;out.put("items",start==null?items:"any");
        out.put("currentCount",count);out.put("gained",count==null?null:Math.max(0,count-baseline));
        out.put("scanPasses",observation==null?0:observation.passes);out.put("scanCursor",observation==null?0:observation.cursor);
        out.put("scanVolume",bounds==null?0:bounds.volume());out.put("targets",lastKnown);out.put("bounds",journal.spec.get("bounds"));
        out.put("initialTargetDiagnostics",diagnostics);
        // Targets it left, and why: will_not_break_here names the fluid beside it (plug or drain that, or pass besideFluid).
        var left=done()?skipped:skipped();out.put("skipped",left.stream().limit(16).toList());out.put("skippedCount",left.size());
        out.put("plugged",plugged.stream().map(MiningProcess::point).toList());out.put("plugFailures",unplugged);
        out.put("blocksBroken",broken);out.put("extraBroken",extraBroken);out.put("extraBrokenAt",extraAt);out.put("dropsLeftInBounds",dropsLeft);out.put("ineffectiveTools",ineffective);out.put("pathRules",BlockRules.applied());out.put("forcedTool",toolSlotTool);
        if(engine!=null){
            var current=engine.getPathingBehavior().getCurrent();
            out.put("goal",done()?finalGoal:String.valueOf(engine.getPathingBehavior().getGoal()));
            out.put("path",current==null?List.of():current.getPath().positions().stream().map(MiningProcess::point).toList());
            out.put("planning",engine.getPathingBehavior().getInProgress().isPresent());
        }
        out.put("completionMeaning",start==null?"matching inventory gain, summed over this job's sessions (each measured from its own start)":"with no items named: any inventory gain, the sum of each item identity's increase (id+meta+NBT; a stack-of-one item by id+meta) since the session's start, summed over sessions; currentCount is that gain");return out;
    }
    /** Watch the block under the pick; a few ticks after it goes (the server's word on what else broke arrives late),
     *  count every solid neighbour that went with it. A tool the game says breaks the block, held on it three times as long
     *  as the game's own estimate with nothing broken, is measured useless for the rest of the job: every tool choice skips
     *  it and the receipt names it. True when the job has ended here. */
    private boolean measure(){
        var over=mc.objectMouseOver;var held=mc.thePlayer.getHeldItem();var tool=held==null?null:MiningTools.toolKind(held);
        BlockPos aim=engine.getInputOverrideHandler().isInputForcedDown(baritone.api.utils.input.Input.CLICK_LEFT)&&over!=null
            &&over.typeOfHit==net.minecraft.util.MovingObjectPosition.MovingObjectType.BLOCK?new BlockPos(over.blockX,over.blockY,over.blockZ):null;
        if(swing!=null){
            var t=swing.target();
            if(world.getBlock(t.getX(),t.getY(),t.getZ())!=swing.block()){broken++;settling.add(new Swing(t,swing.block(),swing.around(),ticks+5,swing.start(),swing.tool(),0));swing=null;}
            else if(aim==null||!aim.equals(t)||!Objects.equals(tool,swing.tool()))swing=null; // let go, looked away or changed tool: that swing ended short
            else if(ticks-swing.start()>swing.limit()){
                if(tool!=null&&MiningTools.ineffective.add(tool)&&ineffective.size()<8)ineffective.add(Map.of("tool",tool,
                    "block",String.valueOf(net.minecraft.block.Block.blockRegistry.getNameForObject(swing.block())),"at",point(t),"heldTicks",ticks-swing.start()));
                swing=null;return false;
            }
        }
        for(var it=settling.iterator();it.hasNext();){
            var s=it.next();if(s.due()>ticks)continue;it.remove();
            for(var e:s.around().entrySet()){
                var q=e.getKey();var now=world.getBlock(q.getX(),q.getY(),q.getZ());var was=e.getValue();
                // A torch or snow layer that dropped when its support went, or gravel that fell, was not broken by the tool.
                if(now==was||now.getMaterial()!=net.minecraft.block.material.Material.air||aimed.contains(q)||was instanceof net.minecraft.block.BlockFalling||!was.getMaterial().blocksMovement())continue;
                extraBroken++;if(extraAt.size()<16)extraAt.add(point(q));
                if(WorkAccess.protection(q,override)!=null){finish("failed","tool_broke_protected_block_at_"+q.getX()+","+q.getY()+","+q.getZ());return true;}
            }
        }
        if(swing!=null||aim==null)return false;
        Map<BlockPos,net.minecraft.block.Block> around=new HashMap<>();
        for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++)for(int dz=-1;dz<=1;dz++){
            var b=world.getBlock(aim.getX()+dx,aim.getY()+dy,aim.getZ()+dz);
            if((dx|dy|dz)!=0&&b.getMaterial()!=net.minecraft.block.material.Material.air)around.put(new BlockPos(aim.getX()+dx,aim.getY()+dy,aim.getZ()+dz),b);}
        if(aimed.size()>4096)aimed.clear();aimed.add(aim);
        var block=world.getBlock(aim.getX(),aim.getY(),aim.getZ());
        double expected=MiningTools.breakTicks(block.getPlayerRelativeBlockHardness(mc.thePlayer,world,aim.getX(),aim.getY(),aim.getZ()));
        // Only a break the game promised can fail: an unbreakable block, or one in a region the harness refuses to edit,
        // stays for reasons that say nothing about the tool.
        int limit=Double.isInfinite(expected)||WorkAccess.protection(aim,override)!=null?Integer.MAX_VALUE:(int)Math.min(72000,Math.max(60,3*expected+20));
        swing=new Swing(aim,block,around,0,ticks,tool,limit);return false;
    }
    private boolean wet(BlockPos p){
        for(int[] d:new int[][]{{0,1,0},{1,0,0},{-1,0,0},{0,0,1},{0,0,-1}})if(world.getBlock(p.getX()+d[0],p.getY()+d[1],p.getZ()+d[2]).getMaterial().isLiquid())return true;
        return false;
    }
    /** Remember the block under the pick while it has fluid beside it; once it is gone it stays remembered until plugged. */
    private void watch(){
        if(breaking!=null&&!world.getBlock(breaking.getX(),breaking.getY(),breaking.getZ()).getMaterial().blocksMovement())return;
        var over=mc.objectMouseOver;breaking=null;
        if(!engine.getInputOverrideHandler().isInputForcedDown(baritone.api.utils.input.Input.CLICK_LEFT)||over==null||over.typeOfHit!=net.minecraft.util.MovingObjectPosition.MovingObjectType.BLOCK)return;
        var p=new BlockPos(over.blockX,over.blockY,over.blockZ);if(wet(p))breaking=p;
    }
    /** The tick after a block beside fluid breaks, before the fluid has moved: put a throwaway block where it was. */
    private boolean plug(){
        if(breaking==null||world.getBlock(breaking.getX(),breaking.getY(),breaking.getZ()).getMaterial().blocksMovement())return false;
        BlockPos p=breaking;breaking=null;
        if(!wet(p))return false;
        if(!engine.getInventoryBehavior().selectThrowawayForLocation(true,p.getX(),p.getY(),p.getZ())){unplugged++;finish("failed","no_throwaway_block_to_plug_fluid");return true;}
        // {neighbour offset, the face of that neighbour which looks at the hole}: floor first, as a player would click
        for(int[] n:new int[][]{{0,-1,0,1},{-1,0,0,5},{1,0,0,4},{0,0,-1,3},{0,0,1,2},{0,1,0,0}}){
            int x=p.getX()+n[0],y=p.getY()+n[1],z=p.getZ()+n[2];var material=world.getBlock(x,y,z).getMaterial();
            if(!material.isSolid()||material.isLiquid())continue;
            var hit=net.minecraft.util.Vec3.createVectorHelper(x+.5-n[0]*.5,y+.5-n[1]*.5,z+.5-n[2]*.5);
            var me=mc.thePlayer;double dx=hit.xCoord-me.posX,dy=hit.yCoord-(me.boundingBox.minY+me.getEyeHeight()),dz=hit.zCoord-me.posZ;
            me.rotationYaw=(float)(Math.toDegrees(Math.atan2(dz,dx))-90);me.rotationPitch=(float)-Math.toDegrees(Math.atan2(dy,Math.sqrt(dx*dx+dz*dz)));
            engine.getInputOverrideHandler().clearAllKeys();engine.getInputOverrideHandler().getBlockBreakHelper().stopBreakingBlock();
            if(mc.playerController.onPlayerRightClick(me,world,me.getHeldItem(),x,y,z,n[3],hit)){me.swingItem();plugged.add(p);return true;}
        }
        unplugged++;return false;
    }
    /** Every target the engine dropped with its reason, unreachable ones included, and the fluid beside those it will not break. */
    private List<Map<String,Object>> skipped(){
        List<Map<String,Object>> out=new ArrayList<>();
        if(mc.thePlayer!=player)return out;
        Map<BlockPos,String> all=new LinkedHashMap<>(skippedNow);for(var p:unreachable)all.put(p,"unreachable");
        all.forEach((p,why)->{
            Map<String,Object> row=new LinkedHashMap<>();row.put("pos",point(p));row.put("block",String.valueOf(net.minecraft.block.Block.blockRegistry.getNameForObject(world.getBlock(p.getX(),p.getY(),p.getZ()))));row.put("why",why);
            if(why.equals("will_not_break_here"))for(int[] d:new int[][]{{0,1,0},{1,0,0},{-1,0,0},{0,0,1},{0,0,-1}}){
                var beside=world.getBlock(p.getX()+d[0],p.getY()+d[1],p.getZ()+d[2]);
                if(beside.getMaterial().isLiquid()){row.put("beside",String.valueOf(net.minecraft.block.Block.blockRegistry.getNameForObject(beside)));row.put("at",List.of(p.getX()+d[0],p.getY()+d[1],p.getZ()+d[2]));break;}
            }
            out.add(row);
        });
        return out;
    }
    private static List<Integer> point(baritone.compat.BlockPos p){return List.of(p.getX(),p.getY(),p.getZ());}
}
