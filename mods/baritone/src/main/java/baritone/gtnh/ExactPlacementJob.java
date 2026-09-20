// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh;

import baritone.gtnh.pathing.*;
import static baritone.gtnh.pathing.WorkSpec.*;
import dev.modbench.control.api.*;
import java.util.*;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.item.*;
import net.minecraft.util.*;

/** Native state-aware placement with DJ2's pre-click and no-destructive-retry receipt. */
final class ExactPlacementJob implements Navigation.Job {
    final Minecraft mc=Minecraft.getMinecraft();
    final WorkSpec.Cell cell;
    final Block expected;
    final InputArbiter.Lease lease;
    final InventorySelection selection;
    final Runnable beforeClick;
    final boolean pillar;
    final Map<String,Object> itemSelector;
    final Map<String,Object> initialBlock;
    final int inventoryBefore;
    final Map<Integer,Integer> dropsBefore;
    String state="selecting_material",reason="";
    int ticks,settling,wait,remaining;
    boolean selected,delivered,nativeReturn;
    Face face;
    Map<String,Object> click;
    record Face(BlockPos support,int side,Vec3 point,int predicted) {}
    ExactPlacementJob(WorkSpec.Cell cell,int slot,int timeout,InputArbiter.Lease lease,Runnable beforeClick) {
        this(cell,slot,timeout,lease,beforeClick,false);
    }
    ExactPlacementJob(WorkSpec.Cell cell,int slot,int timeout,InputArbiter.Lease lease,Runnable beforeClick,boolean pillar) {
        this.pillar=pillar;
        this.cell=cell;this.lease=lease;this.beforeClick=beforeClick;remaining=timeout;expected=BuildingProcess.block(cell);
        if(slot<0)throw new IllegalArgumentException("missing_material");
        selection=new InventorySelection(slot);itemSelector=BuildingProcess.material(cell);
        initialBlock=WorkAccess.observed(mc.theWorld,cell.pos());inventoryBefore=WorkAccess.count(List.of(itemSelector));dropsBefore=drops();
        if(!mc.thePlayer.onGround)throw new IllegalArgumentException("stable_footing_required");
        if(occupied())throw new IllegalArgumentException("placement_target_occupied");
    }
    boolean occupied(){Block current=mc.theWorld.getBlock(cell.pos().x(),cell.pos().y(),cell.pos().z());return !current.isAir(mc.theWorld,cell.pos().x(),cell.pos().y(),cell.pos().z())&&!current.isReplaceable(mc.theWorld,cell.pos().x(),cell.pos().y(),cell.pos().z());}
    void tick() {
        if(done())return;ticks++;
        if(!lease.isActive()){cancel("superseded");return;}
        if(--remaining<=0){finish("failed","timeout");return;}
        BlockPos target=cell.pos();String protection=WorkAccess.protection(target,lease.overrideProtection());if(protection!=null){finish("failed",protection);return;}
        if(delivered) {
            lease.setKeys(Set.of(mc.gameSettings.keyBindSneak.getKeyCode()));
            if(++settling<4)return;
            if(!cell.verify().isEmpty()&&!BuildingProcess.matches(cell)&&settling<40)return;
            if(pillar&&BuildingProcess.matches(cell)&&!mc.thePlayer.onGround&&settling<30)return;
            finish(BuildingProcess.matches(cell)?"succeeded":"failed",BuildingProcess.matches(cell)?"exact_state_observed":"placement_not_verified");return;
        }
        if(!selected) {lease.setKeys(Set.of());selected=selection.tick(lease);if(!selected)return;}
        if(!WorkAccess.item(mc.thePlayer.getHeldItem(),itemSelector)){finish("failed","material_changed");return;}
        if(pillar) {
            double dx=target.x()+.5-mc.thePlayer.posX,dz=target.z()+.5-mc.thePlayer.posZ;
            if(mc.thePlayer.onGround&&(Math.hypot(dx,dz)>.08||Math.hypot(mc.thePlayer.motionX,mc.thePlayer.motionZ)>.025)) {
                lease.look((float)Math.toDegrees(Math.atan2(-dx,dz)),0);lease.setKeys(Math.hypot(dx,dz)>.05?Set.of(mc.gameSettings.keyBindForward.getKeyCode(),mc.gameSettings.keyBindSneak.getKeyCode()):Set.of(mc.gameSettings.keyBindSneak.getKeyCode()));return;
            }
            if(mc.thePlayer.boundingBox.minY<target.y()+1.01) {
                if(!ForgeSnapshot.liveClear(mc.theWorld,mc.thePlayer.posX,Math.max(mc.thePlayer.boundingBox.minY,target.y()),mc.thePlayer.posZ,target.y()+3.05)){finish("failed","pillar_headroom_changed");return;}
                face=null;lease.setKeys(Set.of(mc.gameSettings.keyBindJump.getKeyCode(),mc.gameSettings.keyBindSneak.getKeyCode()));state="pillaring";return;
            }
        } else if(!mc.thePlayer.onGround||Math.hypot(mc.thePlayer.motionX,mc.thePlayer.motionZ)>.035){lease.setKeys(Set.of(mc.gameSettings.keyBindSneak.getKeyCode()));return;}
        // Mods may change selectable geometry while sneaking with this item.
        // Establish that native stance before selecting a support-face point.
        if(!mc.thePlayer.isSneaking()){lease.setKeys(Set.of(mc.gameSettings.keyBindSneak.getKeyCode()));return;}
        if(occupied()){finish("failed","placement_target_changed");return;}
        if(face==null) {
            face=visibleFace();if(face==null){finish("failed","no_visible_face_with_compatible_placement");return;}
            aim(face.point);lease.setKeys(Set.of(mc.gameSettings.keyBindSneak.getKeyCode()));wait=0;state="aiming";if(!pillar)return;
        }
        if(wait-->0)return;
        // Inventory/focus and sneak transitions can move the eye between the
        // proposed face and this input tick. Keep the native ray aimed at the
        // same face point instead of needlessly rejecting a valid work pose.
        aim(face.point);
        dev.modbench.control.NativeTargeting.refresh();
        if(!matches(mc.objectMouseOver,face)){finish("failed","placement_raycast_changed");return;}
        if(!ItemStack.areItemStacksEqual(selection.expected,mc.thePlayer.getHeldItem())){finish("failed","material_changed_before_click");return;}
        if(!mc.thePlayer.isSneaking()){lease.setKeys(Set.of(mc.gameSettings.keyBindSneak.getKeyCode()));return;}
        if(!canPlaceFrom(face.side,mc.thePlayer.getPosition(1))){finish("failed","placement_collision_or_rules_changed");return;}
        mc.thePlayer.sendMotionUpdates();
        click=new LinkedHashMap<>();click.put("support",point(face.support));click.put("face",face.side);click.put("hit",List.of(face.point.xCoord-face.support.x(),face.point.yCoord-face.support.y(),face.point.zCoord-face.support.z()));click.put("predictedMeta",face.predicted);click.put("yaw",mc.thePlayer.rotationYaw);click.put("pitch",mc.thePlayer.rotationPitch);click.put("held",InventorySelection.describe(mc.thePlayer.getHeldItem()));
        // Persist the attempt before native code: a lost response cannot license
        // automatic breaking of a possibly placed block after restart.
        beforeClick.run();delivered=true;state="verifying";
        nativeReturn=mc.playerController.onPlayerRightClick(mc.thePlayer,mc.theWorld,mc.thePlayer.getHeldItem(),face.support.x(),face.support.y(),face.support.z(),face.side,face.point);
        if(nativeReturn)mc.thePlayer.swingItem();
        lease.setKeys(Set.of(mc.gameSettings.keyBindSneak.getKeyCode()));
    }
    void aim(Vec3 point) {
        Vec3 eye=mc.thePlayer.getPosition(1);double dx=point.xCoord-eye.xCoord,dy=point.yCoord-eye.yCoord,dz=point.zCoord-eye.zCoord;
        float yaw=(float)Math.toDegrees(Math.atan2(-dx,dz)),pitch=(float)-Math.toDegrees(Math.atan2(dy,Math.hypot(dx,dz)));
        yaw=(float)number(cell.placement(),"yaw",yaw,-360000,360000);pitch=(float)number(cell.placement(),"pitch",pitch,-90,90);lease.look(yaw,pitch);
    }
    Face visibleFace() {
        return visibleFace(mc.thePlayer.getPosition(1));
    }
    Face visibleFace(Vec3 eye) {
        var world=mc.theWorld;ItemStack held=selection.expected;
        for(int side=0;side<6;side++) {
            if(cell.placement().containsKey("face")&&integer(cell.placement(),"face",0,0,5)!=side)continue;
            if(!canPlaceFrom(side,eye))continue;
            int[] d=WorkAccess.SIDES[side];BlockPos support=new BlockPos(cell.pos().x()-d[0],cell.pos().y()-d[1],cell.pos().z()-d[2]);
            if(!ForgeSnapshot.loaded(world,support.x(),support.y(),support.z()))continue;
            Block block=world.getBlock(support.x(),support.y(),support.z());
            // Native ItemBlock use replaces a replaceable hit block in place;
            // it does not place in our intended adjacent cell. Such a support
            // could edit outside the blueprint even when its ray is visible.
            if(block.isAir(world,support.x(),support.y(),support.z())||block.isReplaceable(world,support.x(),support.y(),support.z())||ForgeFluids.fluid(block))continue;
            block.setBlockBoundsBasedOnState(world,support.x(),support.y(),support.z());var box=dev.modbench.control.NativeTargeting.withContext(()->block.getSelectedBoundingBoxFromPool(world,support.x(),support.y(),support.z()));if(box==null)continue;
            // Retain BuilderProcess's bounding-box face sampling; unit-cube
            // points are insufficient for slabs and modded collision geometry.
            for(double a:new double[]{.5,.2,.8})for(double b:new double[]{.5,.2,.8}) {
                double[] low={box.minX,box.minY,box.minZ},high={box.maxX,box.maxY,box.maxZ};int axis=side<2?1:side<4?2:0;
                double[] xyz=new double[3];int index=0;for(int i=0;i<3;i++)xyz[i]=i==axis?(side%2==0?low[i]:high[i]):low[i]+(high[i]-low[i])*(index++==0?a:b);
                if(cell.placement().containsKey("hit")){List<?> hit=list(cell.placement().get("hit"));if(hit.size()!=3)throw new IllegalArgumentException("hit must have three local coordinates");for(int i=0;i<3;i++)xyz[i]=new int[]{support.x(),support.y(),support.z()}[i]+number(Map.of("hit",hit.get(i)),"hit",0,-16,16);}
                Vec3 point=Vec3.createVectorHelper(xyz[0],xyz[1],xyz[2]);if(eye.distanceTo(point)>mc.playerController.getBlockReachDistance()-.05)continue;
                if(cell.placement().containsKey("yaw")||cell.placement().containsKey("pitch")) {
                    double yaw=Math.toRadians(number(cell.placement(),"yaw",Math.toDegrees(Math.atan2(eye.xCoord-point.xCoord,point.zCoord-eye.zCoord)),-360000,360000));
                    double pitch=Math.toRadians(number(cell.placement(),"pitch",-Math.toDegrees(Math.atan2(point.yCoord-eye.yCoord,Math.hypot(point.xCoord-eye.xCoord,point.zCoord-eye.zCoord))),-90,90));
                    double reach=mc.playerController.getBlockReachDistance();
                    Vec3 end=eye.addVector(-Math.sin(yaw)*Math.cos(pitch)*reach,-Math.sin(pitch)*reach,Math.cos(yaw)*Math.cos(pitch)*reach);
                    var hit=dev.modbench.control.NativeTargeting.trace(world,Vec3.createVectorHelper(eye.xCoord,eye.yCoord,eye.zCoord),end,false,false,true);
                    if(hit==null||hit.typeOfHit!=MovingObjectPosition.MovingObjectType.BLOCK||hit.blockX!=support.x()||hit.blockY!=support.y()||hit.blockZ!=support.z()||hit.sideHit!=side)continue;
                    if(cell.placement().containsKey("hit")&&hit.hitVec.distanceTo(point)>.03)continue;
                    point=hit.hitVec;xyz=new double[]{point.xCoord,point.yCoord,point.zCoord};
                }
                int meta=expected.onBlockPlaced(world,cell.pos().x(),cell.pos().y(),cell.pos().z(),side,(float)(xyz[0]-support.x()),(float)(xyz[1]-support.y()),(float)(xyz[2]-support.z()),dev.modbench.control.NativePlacement.initialMetadata(held));
                float yaw=(float)number(cell.placement(),"yaw",Math.toDegrees(Math.atan2(eye.xCoord-point.xCoord,point.zCoord-eye.zCoord)),-360000,360000);
                meta=PlacementStateAdapters.predict(expected,world,cell.pos(),meta,yaw,eye);
                // 1.7.10 also has onBlockPlacedBy, unlike the 1.12 state-for-
                // placement contract. Explicitly opt into post-placement state
                // verification when a mod sets orientation there.
                if(meta!=cell.meta()&&!bool(cell.placement(),"verifyAfterPlacement",false))continue;
                Face candidate=new Face(support,side,point,meta);
                Vec3 end=point.addVector(d[0]*-.001,d[1]*-.001,d[2]*-.001);
                // Same selectable-block ray as vanilla input and Baritone's
                // RayTraceUtils, including blocks without collision boxes.
                if(matches(dev.modbench.control.NativeTargeting.trace(world,Vec3.createVectorHelper(eye.xCoord,eye.yCoord,eye.zCoord),end,false,false,true),candidate))return candidate;
            }
        }return null;
    }
    boolean canPlaceFrom(int side,Vec3 eye) {
        BlockPos p=cell.pos();var world=mc.theWorld;
        int[] d=WorkAccess.SIDES[side];int sx=p.x()-d[0],sy=p.y()-d[1],sz=p.z()-d[2];
        if(!ForgeSnapshot.loaded(world,p.x(),p.y(),p.z())||!ForgeSnapshot.loaded(world,sx,sy,sz))return false;
        Block support=world.getBlock(sx,sy,sz);
        if(support.isAir(world,sx,sy,sz)||support.isReplaceable(world,sx,sy,sz)||ForgeFluids.fluid(support))return false;
        // Upstream BuilderProcess tests World.mayPlace before proposing work.
        // In 1.7.10 this is canPlaceEntityOnSide; preserve native mod callbacks
        // and other-entity collision checks. Exclude the player's old position
        // while evaluating a future pose, then check its translated body below.
        if(!world.canPlaceEntityOnSide(expected,p.x(),p.y(),p.z(),false,side,mc.thePlayer,selection.expected))return false;
        var collision=expected.getCollisionBoundingBoxFromPool(world,p.x(),p.y(),p.z());
        if(collision==null)return true;
        Vec3 actualEye=mc.thePlayer.getPosition(1);
        var body=mc.thePlayer.boundingBox.getOffsetBoundingBox(eye.xCoord-actualEye.xCoord,eye.yCoord-actualEye.yCoord,eye.zCoord-actualEye.zCoord);
        return !collision.intersectsWith(body);
    }
    static boolean reachable(WorkSpec.Cell cell,int slot) {
        if(slot<0||!WorkAccess.MC.thePlayer.onGround||WorkAccess.distance(cell.pos())>6)return false;
        try{return new ExactPlacementJob(cell,slot,1,null,()->{}).visibleFace()!=null;}catch(IllegalArgumentException error){return false;}
    }
    static boolean reachableFrom(WorkSpec.Cell cell,int slot,WorkAccess.Pose pose) {
        if(slot<0)return false;
        try{return new ExactPlacementJob(cell,slot,1,null,()->{}).visibleFace(WorkAccess.eyeAt(pose))!=null;}catch(IllegalArgumentException error){return false;}
    }
    static boolean matches(MovingObjectPosition hit,Face f){return hit!=null&&hit.typeOfHit==MovingObjectPosition.MovingObjectType.BLOCK&&hit.blockX==f.support.x()&&hit.blockY==f.support.y()&&hit.blockZ==f.support.z()&&hit.sideHit==f.side;}
    Map<Integer,Integer> drops(){Map<Integer,Integer> out=new HashMap<>();for(Object o:mc.theWorld.loadedEntityList)if(o instanceof net.minecraft.entity.item.EntityItem e&&!e.isDead&&e.getDistanceSq(cell.pos().x()+.5,cell.pos().y()+.5,cell.pos().z()+.5)<36&&WorkAccess.item(e.getEntityItem(),itemSelector))out.put(e.getEntityId(),e.getEntityItem().stackSize);return out;}
    void finish(String state,String reason){if(done())return;this.state=state;this.reason=reason;selection.close();if(lease.isActive())lease.setKeys(Set.of());}
    @Override public void cancel(String reason){finish("cancelled",reason);}
    @Override public boolean done(){return Set.of("succeeded","failed","cancelled").contains(state);}
    @Override public boolean succeeded(){return state.equals("succeeded");}
    @Override public Map<String,Object> status(){Map<String,Object> out=new LinkedHashMap<>();out.put("action","place_exact");out.put("state",state);out.put("reason",reason);out.put("target",point(cell.pos()));out.put("expected",Map.of("id",cell.id(),"meta",cell.meta()));out.put("inputDelivered",delivered);out.put("nativeReturn",nativeReturn);out.put("before",initialBlock);out.put("click",click);out.put("ticks",ticks);
        int after=mc.thePlayer==null?inventoryBefore:WorkAccess.count(List.of(itemSelector));out.put("inventoryBefore",inventoryBefore);out.put("inventoryAfter",after);out.put("inventoryDelta",after-inventoryBefore);out.put("serverAcknowledged",false);
        if(mc.theWorld!=null){out.put("actual",WorkAccess.observed(mc.theWorld,cell.pos()));if(!cell.verify().isEmpty()){out.put("verify",cell.verify());out.put("pickedItem",InventorySelection.describe(WorkAccess.picked(mc.theWorld,cell.pos())));}int added=0;for(var e:drops().entrySet())added+=Math.max(0,e.getValue()-dropsBefore.getOrDefault(e.getKey(),0));out.put("newNearbyDrops",added);out.put("lossAttribution","unproven");}return out;}
}
