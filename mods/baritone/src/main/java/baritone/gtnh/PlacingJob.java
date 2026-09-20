// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh;

import dev.modbench.api.ControlRegistry;
import baritone.compat.BlockPos;
import baritone.gtnh.pathing.TerrainGrid;
import dev.modbench.api.InputArbiter;
import dev.modbench.api.Navigation;
import java.util.*;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

/** One inventory-funded cube, placed through normal right-click input and collision checks. */
final class PlacingJob implements Navigation.Job {
    private final Minecraft mc=Minecraft.getMinecraft();
    private final BlockPos target,footing;
    private final boolean overrideProtection;
    private final Block expected;
    private final float health;
    private final int initialCount;
    private World world=mc.theWorld;
    private Object player=mc.thePlayer;
    private InputArbiter.Lease lease;
    private InventorySelection selection;
    private Map<String,Object> selected;
    private String state="selecting_block",reason="";
    private int ticks,remaining,settling;
    private boolean selectedReady;
    private record Face(BlockPos block,int side,Vec3 point) {}
    PlacingJob(BlockPos target,int timeoutTicks,boolean overrideProtection) {
        this.target=target;remaining=timeoutTicks;this.overrideProtection=overrideProtection;
        if(world==null || mc.thePlayer==null || mc.currentScreen!=null) throw new IllegalArgumentException("placement requires player with GUI closed");
        if(timeoutTicks<1 || timeoutTicks>6000) throw new IllegalArgumentException("timeoutTicks must be 1..6000");
        int x=(int)Math.floor(mc.thePlayer.posX),y=(int)Math.floor(mc.thePlayer.boundingBox.minY+.001),z=(int)Math.floor(mc.thePlayer.posZ);
        footing=new BlockPos(x,y-1,z);health=mc.thePlayer.getHealth();
        if(!mc.thePlayer.onGround || ForgeSnapshot.classify(world,x,y-1,z)!=TerrainGrid.SUPPORT) throw new IllegalArgumentException("stable_full_block_footing_required");
        if(!ForgeSnapshot.loaded(world,target.getX(),target.getY(),target.getZ()) || !world.isAirBlock(target.getX(),target.getY(),target.getZ())) throw new IllegalArgumentException("placement_target_must_be_loaded_air");
        if(Math.abs(target.getX()-x)+Math.abs(target.getZ()-z)>4 || Math.abs(target.getY()-y)>3) throw new IllegalArgumentException("placement_target_out_of_reach");
        int slot=PlacementItems.slot();if(slot<0) throw new IllegalArgumentException("no_placement_material");
        selection=new InventorySelection(slot);selected=selection.status();expected=Block.getBlockFromItem(selection.expected.getItem());initialCount=selection.expected.stackSize;
        String unsafe=unsafe();if(unsafe!=null) throw new IllegalArgumentException(unsafe);
        lease=ControlRegistry.controls().arbiter().acquire("baritone_placing",this::cancel,overrideProtection,false);
        if(!lease.isActive()) finish("cancelled","input_unavailable");
    }
    void tick() {
        if(done()) return;ticks++;
        if(mc.theWorld!=world || mc.thePlayer!=player || mc.currentScreen!=null&&!ControlRegistry.controls().ownsPlayerInventory(lease)) {cancel("world_or_gui_changed");return;}
        if(!lease.isActive()) {cancel("superseded");return;}
        if(--remaining<=0) {finish("failed","timeout");return;}
        String unsafe=unsafe();if(unsafe!=null) {finish("failed",unsafe);return;}
        Block present=world.getBlock(target.getX(),target.getY(),target.getZ());
        if(present==expected && world.getBlockMetadata(target.getX(),target.getY(),target.getZ())==0) {
            lease.setKeys(Set.of(mc.gameSettings.keyBindSneak.getKeyCode()));state="settling";
            if(++settling>=10) finish("succeeded","block_observed");return;
        }
        if(settling>0) {finish("failed","server_rejected_placement");return;}
        if(!world.isAirBlock(target.getX(),target.getY(),target.getZ())) {finish("failed","placement_target_changed");return;}
        if(!selectedReady) {
            lease.setKeys(Set.of());
            selectedReady=selection.tick(lease);return;
        }
        ItemStack held=mc.thePlayer.getHeldItem();
        if(!PlacementItems.usable(held) || Block.getBlockFromItem(held.getItem())!=expected || held.stackSize!=initialCount) {finish("failed","placement_inventory_changed");return;}
        Face face=visibleFace();
        if(face==null) {
            // Floor bridging requires seeing the supporting block's side. Sneak a bounded
            // distance onto its edge, with the original solid footprint still underneath.
            int dx=target.getX()-footing.getX(),dz=target.getZ()-footing.getZ();
            if(target.getY()!=footing.getY() || Math.abs(dx)+Math.abs(dz)!=1) {finish("failed","no_visible_attachment_face");return;}
            double ex=footing.getX()+.5+dx*.58,ez=footing.getZ()+.5+dz*.58;
            double ax=ex-mc.thePlayer.posX-mc.thePlayer.motionX*2.2,az=ez-mc.thePlayer.posZ-mc.thePlayer.motionZ*2.2;
            state="positioning";lease.look((float)Math.toDegrees(Math.atan2(-ax,az)),75);
            Set<Integer> keys=new LinkedHashSet<>();keys.add(mc.gameSettings.keyBindSneak.getKeyCode());
            if(Math.hypot(ax,az)>.02) keys.add(mc.gameSettings.keyBindForward.getKeyCode());
            lease.setKeys(keys);return;
        }
        state="placing";Vec3 eye=mc.thePlayer.getPosition(1);
        double dx=face.point.xCoord-eye.xCoord,dy=face.point.yCoord-eye.yCoord,dz=face.point.zCoord-eye.zCoord;
        lease.look((float)Math.toDegrees(Math.atan2(-dx,dz)),(float)-Math.toDegrees(Math.atan2(dy,Math.hypot(dx,dz))));
        Set<Integer> keys=new LinkedHashSet<>();keys.add(mc.gameSettings.keyBindSneak.getKeyCode());
        if(matches(mc.objectMouseOver,face) && Math.hypot(mc.thePlayer.motionX,mc.thePlayer.motionZ)<.02) keys.add(mc.gameSettings.keyBindUseItem.getKeyCode());
        lease.setKeys(keys);
    }
    private String unsafe() {
        String protectedRegion=dev.modbench.api.ControlRegistry.memory().editProblem(target.getX(),target.getY(),target.getZ(),overrideProtection,false);
        if(protectedRegion!=null) return protectedRegion;
        if(mc.thePlayer.getHealth()<health || mc.thePlayer.isBurning()) return "damage_or_fire";
        if(!mc.thePlayer.onGround || Math.abs(mc.thePlayer.boundingBox.minY-(footing.getY()+1))>.01) return "footing_changed";
        if(ForgeSnapshot.classify(world,footing.getX(),footing.getY(),footing.getZ())!=TerrainGrid.SUPPORT
            || Math.abs(mc.thePlayer.posX-(footing.getX()+.5))>.76 || Math.abs(mc.thePlayer.posZ-(footing.getZ()+.5))>.76) return "footing_drifted";
        if(!ForgeSnapshot.safeBody(world,mc.thePlayer.posX,mc.thePlayer.boundingBox.minY,mc.thePlayer.posZ,false)) return "unsafe_placement_body";
        for(int[] d:new int[][]{{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}}) {
            byte c=ForgeSnapshot.classify(world,target.getX()+d[0],target.getY()+d[1],target.getZ()+d[2]);
            if(c==TerrainGrid.UNKNOWN || c==TerrainGrid.HAZARD || c==TerrainGrid.WATER) return "unsafe_placement_neighbor";
        }
        return null;
    }
    private Face visibleFace() {
        int[][] directions={{0,-1,0,1},{0,1,0,0},{-1,0,0,5},{1,0,0,4},{0,0,-1,3},{0,0,1,2}};
        Vec3 eye=mc.thePlayer.getPosition(1);
        for(int[] d:directions) {
            BlockPos support=new BlockPos(target.getX()+d[0],target.getY()+d[1],target.getZ()+d[2]);
            if(ForgeSnapshot.classify(world,support.getX(),support.getY(),support.getZ())!=TerrainGrid.SUPPORT || world.getTileEntity(support.getX(),support.getY(),support.getZ())!=null) continue;
            Vec3 point=Vec3.createVectorHelper(support.getX()+.5-d[0]*.499,support.getY()+.5-d[1]*.499,support.getZ()+.5-d[2]*.499);
            Face face=new Face(support,d[3],point);
            if(eye.distanceTo(point)<=mc.playerController.getBlockReachDistance()-.1 && matches((net.minecraft.util.MovingObjectPosition)dev.modbench.api.ControlRegistry.targeting().trace(world,eye,point,false,true,false),face)) return face;
        }
        return null;
    }
    private boolean matches(MovingObjectPosition hit,Face face) {
        return hit!=null && hit.typeOfHit==MovingObjectPosition.MovingObjectType.BLOCK && hit.blockX==face.block.getX() && hit.blockY==face.block.getY() && hit.blockZ==face.block.getZ() && hit.sideHit==face.side;
    }
    private void finish(String state,String reason) {
        if(done()) return;this.state=state;this.reason=reason;
        selection.close();
        if(lease!=null) lease.close();
        world=null;player=null;
    }
    @Override public void cancel(String reason) {finish("cancelled",reason);}
    @Override public boolean done() {return state.equals("succeeded")||state.equals("failed")||state.equals("cancelled");}
    @Override public boolean succeeded() {return state.equals("succeeded");}
    @Override public Map<String,Object> status() {
        Map<String,Object> out=new LinkedHashMap<>();out.put("available",true);out.put("action","place_block");out.put("state",state);out.put("reason",reason);
        out.put("overrideProtection",overrideProtection);out.put("target",List.of(target.getX(),target.getY(),target.getZ()));out.put("ticks",ticks);out.put("material",selected);out.put("controlOwned",lease!=null&&lease.isActive());
        out.put("serverAcknowledged",false);out.put("completionMeaning","block observed in client world after normal placement");return out;
    }
}
