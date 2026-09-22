// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh;

import dev.modbench.api.ControlRegistry;
import baritone.compat.BlockPos;
import dev.modbench.api.InputArbiter;
import dev.modbench.api.Navigation;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

/** One survival block, with optional automatic tools. */
final class MiningJob implements Navigation.Job {
    private final Minecraft mc;
    private final BlockPos target;
    private World world;
    private Object player;
    private final Block original;
    private final int metadata;
    private InputArbiter.Lease lease;
    private final boolean autoTool,overrideProtection;
    private InventorySelection selection;
    private final List<Map<String,Object>> toolsUsed=new ArrayList<>();
    private String state="mining",reason="";
    private int remaining,ticks,settling,aimMismatchTicks;
    private Map<String,Object> actualAim=Map.of();

    MiningJob(Minecraft mc,BlockPos target,int timeoutTicks,boolean autoTool,boolean overrideProtection) {
        this.mc=mc; this.target=target;
        this.overrideProtection=overrideProtection;this.autoTool=autoTool;
        if(mc.theWorld==null||mc.thePlayer==null||mc.currentScreen!=null) throw new IllegalArgumentException("mining needs a player with GUI closed");
        if(timeoutTicks<1||timeoutTicks>6000) throw new IllegalArgumentException("timeoutTicks must be 1..6000");
        world=mc.theWorld;player=mc.thePlayer;remaining=timeoutTicks;
        if(target.getY()<1||target.getY()>254||!ForgeSnapshot.loaded(world,target.getX(),target.getY(),target.getZ())) throw new IllegalArgumentException("target not loaded");
        original=world.getBlock(target.getX(),target.getY(),target.getZ());metadata=world.getBlockMetadata(target.getX(),target.getY(),target.getZ());
        if(mc.thePlayer.getHealth()<=0||original.isAir(world,target.getX(),target.getY(),target.getZ())||ForgeFluids.fluid(original)) throw new IllegalArgumentException("target must be a non-fluid block; use native held-item/block interaction for fluids");
        if(!autoTool && !original.canHarvestBlock(mc.thePlayer,metadata)) throw new IllegalArgumentException("selected tool cannot harvest target");
        if(!autoTool && original.getPlayerRelativeBlockHardness(mc.thePlayer,world,target.getX(),target.getY(),target.getZ())<=0) throw new IllegalArgumentException("target cannot be mined with selected tool");
        String unsafe=unsafe();
        if(unsafe!=null) throw new IllegalArgumentException(unsafe);
        if(aim()==null) throw new IllegalArgumentException("target is occluded or outside normal reach");
        if(autoTool) selectTool();
        lease=ControlRegistry.controls().arbiter().acquire("baritone_mining",this::cancel,overrideProtection,false);
        if(!lease.isActive()) finish("cancelled","input_unavailable");
    }

    void tick() {
        if(done()) return;
        ticks++;
        if(WorkAccess.died(player)) {finish("failed","player_died");return;}
        if(mc.theWorld!=world||mc.thePlayer!=player||mc.currentScreen!=null&&!ControlRegistry.controls().ownsPlayerInventory(lease)) {cancel("world_or_gui_changed");return;}
        if(!lease.isActive()) {cancel("superseded");return;}
        if(--remaining<=0) {finish("failed","timeout");return;}
        // A real released-input tick is required when focus was just restored:
        // 1.7.10 sets leftClickCounter=10000 after closing a GUI. Holding attack
        // immediately can leave it suppressed for the entire job. Keep native
        // click/harvest hooks rather than bypassing the counter or break speed.
        if(ticks==1){lease.setKeys(Set.of());state="arming";return;}
        String unsafe=unsafe();
        if(unsafe!=null) {finish("failed",unsafe);return;}
        boolean changed=world.getBlock(target.getX(),target.getY(),target.getZ())!=original || world.getBlockMetadata(target.getX(),target.getY(),target.getZ())!=metadata;
        if(changed) {
            lease.setKeys(Set.of());
            state="settling";
            if(++settling>=4) finish("succeeded","target_changed");
            return;
        }
        if(settling>0) {finish("failed","server_restored_target");return;}
        if(selection!=null) {
            lease.setKeys(Set.of());state="selecting_tool";
            if(!selection.tick(lease)) return;
            toolsUsed.add(selection.status());selection=null;state="mining";
        }
        if(autoTool && (MiningTools.rejected(mc.thePlayer.getHeldItem())!=null || !original.canHarvestBlock(mc.thePlayer,metadata))) {
            lease.setKeys(Set.of(mc.gameSettings.keyBindSneak.getKeyCode()));mc.playerController.resetBlockRemoving();selectTool();return;
        }
        if(!original.canHarvestBlock(mc.thePlayer,metadata)) {finish("failed","tool_changed_or_broken");return;}
        Vec3 point=aim();
        if(point==null) {finish("failed","target_occluded_or_out_of_reach");return;}
        Vec3 eye=mc.thePlayer.getPosition(1);
        double dx=point.xCoord-eye.xCoord,dy=point.yCoord-eye.yCoord,dz=point.zCoord-eye.zCoord;
        lease.look((float)Math.toDegrees(Math.atan2(-dx,dz)),(float)-Math.toDegrees(Math.atan2(dy,Math.hypot(dx,dz))));
        Set<Integer> keys=new LinkedHashSet<>();
        keys.add(mc.gameSettings.keyBindSneak.getKeyCode());
        // The regular client pipeline raycasts and sends the survival digging packets.
        // Wait for that raycast to match; never hold attack on an intervening block/entity.
        dev.modbench.api.ControlRegistry.targeting().refresh();
        var hit=mc.objectMouseOver;
        actualAim=hit==null?Map.of("type","MISS"):hit.typeOfHit==MovingObjectPosition.MovingObjectType.BLOCK
            ?Map.of("type","BLOCK","pos",List.of(hit.blockX,hit.blockY,hit.blockZ))
            :Map.of("type",hit.typeOfHit.toString());
        if(matches(hit)) {keys.add(mc.gameSettings.keyBindAttack.getKeyCode());aimMismatchTicks=0;state="mining";}
        else if(++aimMismatchTicks>=20) {finish("failed","native_raycast_does_not_match_target");return;}
        else state="aiming";
        lease.setKeys(keys);
    }

    /** The model's own protected regions. Footing, fluids and damage are its to judge; the clock's guards pause on the damage. */
    private String unsafe() {
        return dev.modbench.api.ControlRegistry.memory().editProblem(target.getX(),target.getY(),target.getZ(),overrideProtection,false);
    }
    private void selectTool() {
        MiningTools.Choice choice=MiningTools.best(world,target,null);
        if(choice==null) throw new IllegalArgumentException("no_eligible_harvest_tool");
        selection=new InventorySelection(choice.slot());
    }

    private boolean matches(MovingObjectPosition hit) {
        return hit!=null && hit.typeOfHit==MovingObjectPosition.MovingObjectType.BLOCK
            && hit.blockX==target.getX()&&hit.blockY==target.getY()&&hit.blockZ==target.getZ();
    }
    private Vec3 aim() {
        return reachable(mc,world,target);
    }
    static Vec3 reachable(Minecraft mc,World world,BlockPos target) {
        return reachable(mc,world,target,mc.thePlayer.getPosition(1));
    }
    static Vec3 reachable(Minecraft mc,World world,BlockPos target,Vec3 eye) {
        for(double y:new double[]{.95,.5,.05}) for(double x:new double[]{.5,.15,.85}) for(double z:new double[]{.5,.15,.85}) {
            Vec3 point=Vec3.createVectorHelper(target.getX()+x,target.getY()+y,target.getZ()+z);
            if(eye.distanceTo(point)>mc.playerController.getBlockReachDistance()-.1) continue;
            // Match vanilla targeting and upstream RayTraceUtils: selectable
            // blocks can have no collision box. Ignoring them invents a line of
            // sight through plants and other modded non-colliding blocks.
            var hit=(net.minecraft.util.MovingObjectPosition)dev.modbench.api.ControlRegistry.targeting().trace(world,Vec3.createVectorHelper(eye.xCoord,eye.yCoord,eye.zCoord),point,false,false,true);
            if(hit!=null&&hit.typeOfHit==MovingObjectPosition.MovingObjectType.BLOCK&&hit.blockX==target.getX()&&hit.blockY==target.getY()&&hit.blockZ==target.getZ()) return point;
        }
        return null;
    }
    private void finish(String state,String reason) {
        if(done()) return;
        this.state=state;this.reason=reason;
        if(selection!=null) selection.close();
        if(lease!=null) lease.close();
        if(mc.thePlayer==player && mc.playerController!=null) mc.playerController.resetBlockRemoving();
        world=null;player=null;
    }
    @Override public void cancel(String reason) {finish("cancelled",reason);}
    @Override public boolean done() {return state.equals("succeeded")||state.equals("failed")||state.equals("cancelled");}
    @Override public boolean succeeded() {return state.equals("succeeded");}
    @Override public Map<String,Object> status() {
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("available",true);out.put("state",state);out.put("reason",reason);out.put("action","mine_block");
        out.put("target",List.of(target.getX(),target.getY(),target.getZ()));out.put("ticks",ticks);
        out.put("actualAim",actualAim);out.put("aimMismatchTicks",aimMismatchTicks);
        out.put("overrideProtection",overrideProtection);out.put("autoTool",autoTool);out.put("toolsUsed",toolsUsed);
        out.put("controlOwned",lease!=null&&lease.isActive());out.put("serverAcknowledged",false);
        out.put("completionMeaning","target changed in client world; item collection is separate");
        return out;
    }
}
