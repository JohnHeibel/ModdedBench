// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.client;

import dev.modbench.api.ControlRegistry;
import com.google.gson.*;
import dev.modbench.bridge.*;
import dev.modbench.api.InputArbiter;
import java.util.*;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.*;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.*;
import net.minecraft.util.*;

/** Native survival actions with bounded lifetimes, exact targets and shared input ownership. */
final class InteractionOperations {
    static final String[] METHODS={"use_block","use_entity","attack_entity","use_item","eat","select_hotbar","combat"};
    private final Minecraft mc=Minecraft.getMinecraft();
    private final Map<Entity,String> handles=new WeakHashMap<>();
    private Job active;
    private JsonObject last=Json.object("state","idle");

    String handle(Entity e) {return handles.computeIfAbsent(e,key->UUID.randomUUID().toString());}
    static boolean hostile(Entity e,EntityPlayer p) {
        return e instanceof IMob; // by type: whom a mob is targeting is server state the client never receives
    }
    JsonObject entity(Entity e) {
        JsonObject out=Json.object("entityId",e.getEntityId(),"handle",handle(e),"handleScope","client_instance",
            "type",EntityList.getEntityString(e),"name",e.getCommandSenderName(),"pos",Json.array(e.posX,e.boundingBox.minY,e.posZ),
            "distance",e.getDistanceToEntity(mc.thePlayer),"visible",mc.thePlayer.canEntityBeSeen(e),"hostile",hostile(e,mc.thePlayer),"dead",e.isDead);
        if(e instanceof EntityLivingBase living) {out.addProperty("health",finite(living.getHealth()));out.addProperty("maxHealth",finite(living.getMaxHealth()));out.addProperty("hurtTime",living.hurtTime);}
        if(e instanceof net.minecraft.entity.item.EntityItem drop) out.add("stack",Stacks.json(drop.getEntityItem()));
        return out;
    }
    JsonObject entities(JsonObject params) {
        if(mc.thePlayer==null||mc.theWorld==null) throw new IllegalArgumentException("player is not in a world");
        double radius=Json.number(params,"radius",32,1,128);int limit=Json.integer(params,"limit",128,1,256);
        List<Entity> found=new ArrayList<>();for(Object value:mc.theWorld.loadedEntityList) {Entity e=(Entity)value;if(e!=mc.thePlayer&&!e.isDead&&e.getDistanceSqToEntity(mc.thePlayer)<=radius*radius) found.add(e);}
        found.sort(Comparator.comparingDouble(e->e.getDistanceSqToEntity(mc.thePlayer)));
        JsonArray out=new JsonArray();for(int i=0;i<Math.min(limit,found.size());i++) out.add(entity(found.get(i)));
        return Json.object("entities",out,"total",found.size(),"truncated",found.size()>limit,"dimension",mc.theWorld.provider.dimensionId);
    }
    Object start(Request r) {
        if(mc.thePlayer==null||mc.theWorld==null||mc.thePlayer.getHealth()<=0) throw new IllegalArgumentException("live player required");
        if(mc.currentScreen!=null) throw new IllegalArgumentException("close GUI before targeted actions");
        Job job=new Job(r); // Validate before acquiring controls or changing a slot.
        active=job;
        try {job.start();}catch(Exception|LinkageError e) {job.finish("failed",e.toString());}
        return null;
    }
    Object status() {return active==null?last:active.receipt("running",null);}
    void maintain() {
        Job job=active;if(job==null)return;
        if(job.r.isDone()||job.r.expired()||!job.r.session.connected) job.finish("cancelled","request cancelled, expired or disconnected");
        else if(mc.theWorld!=job.world||mc.thePlayer!=job.player||mc.thePlayer.getHealth()<=0) job.finish("cancelled","player/world changed or died");
        else if(job.delivered&&job.ownUse&&!job.player.isUsingItem()) {
            // Server completion can arrive between ticks. Release before vanilla
            // sees a held use key and activates the block now under the crosshair.
            job.stopUse();job.deliveredAt=job.elapsed;
        }
    }
    void tick() {Job job=active;if(job!=null) try {job.tick();}catch(Exception|LinkageError e) {job.finish("failed",e.toString());}}
    void cancel(String reason) {if(active!=null)active.finish("cancelled",reason);}
    void itemUseFinished() {
        Job job=active;
        if(job==null||!job.delivered||!job.ownUse)return;
        // Run before vanilla applies its server completion packet. Do not call
        // onStoppedUsingItem: vanilla still needs the held item to finish it.
        job.ownUse=false;job.consumptionAcknowledged=true;job.deliveredAt=job.elapsed;
        if(job.lease!=null)job.lease.close();
    }

    private final class Job {
        final Request r;final JsonObject p;final String kind;
        final net.minecraft.client.entity.EntityClientPlayerMP player=mc.thePlayer;
        final net.minecraft.client.multiplayer.WorldClient world=mc.theWorld;
        final int duration,settle,interval;final double range;
        final boolean sneak,combat;
        final JsonObject before;
        InputArbiter.Lease lease;
        Entity target;String targetHandle;
        Vec3 point;int x,y,z,face;
        boolean blockTarget,fluidTarget,delivered,accepted,ownUse,delivering,finished,consumptionAcknowledged;
        int elapsed,deliveredAt,attacks,deaths,lastAttack=-1000,nativeUseTicks;
        String revokedDuringDelivery;
        final long refusalMark=ControlRegistry.memory().refusals();
        String outcome="completed";
        Job(Request r) {
            this.r=r;p=r.params;kind=r.method.substring(4);combat=kind.equals("combat");
            duration=Json.integer(p,"ticks",combat?200:kind.equals("eat")?400:40,1,72000);
            settle=Json.integer(p,"settleTicks",4,1,100);interval=Json.integer(p,"intervalTicks",10,1,200);
            range=Json.number(p,"range",3,0.5,3);sneak=Json.bool(p,"sneak",false);
            if(p.has("expectedHeld")&&!Stacks.expected(player.getHeldItem(),p.get("expectedHeld"))) throw new IllegalArgumentException("stale_held: observed stack changed");
            if(kind.equals("select_hotbar")) Json.integer(p,"slot",-1,0,8);
            if(kind.equals("use_block")||p.has("x")) {
                if(!p.has("x")||!p.has("y")||!p.has("z")) throw new IllegalArgumentException("x,y,z required");
                x=Json.integer(p,"x",0,-30000000,30000000);y=Json.integer(p,"y",0,0,255);z=Json.integer(p,"z",0,-30000000,30000000);
                if(!world.blockExists(x,y,z)||world.getChunkFromChunkCoords(x>>4,z>>4).isEmpty()) throw new IllegalArgumentException("target block is not loaded");
                blockTarget=true;fluidTarget=Json.bool(p,"fluid",false);
                face=p.has("face")||p.has("hit")||fluidTarget?Json.integer(p,"face",1,0,5):visibleFace();
                double[] hit=facePoint(face);
                if(p.has("hit")) {JsonArray a=p.getAsJsonArray("hit");if(a.size()!=3) throw new IllegalArgumentException("hit must have 3 local coordinates");for(int i=0;i<3;i++){hit[i]=a.get(i).getAsDouble();if(!Double.isFinite(hit[i])||Math.abs(hit[i])>16)throw new IllegalArgumentException("hit coordinates must be finite and within 16 blocks of the target");}}
                point=Vec3.createVectorHelper(x+hit[0],y+hit[1],z+hit[2]);checkBlock();
            }
            if(kind.equals("use_entity")||kind.equals("attack_entity")||p.has("entityId")) {
                target=world.getEntityByID(Json.integer(p,"entityId",-1,0,Integer.MAX_VALUE));
                if(target==null||target==player||target.isDead) throw new IllegalArgumentException("target entity is not loaded");
                targetHandle=handle(target);
                if(p.has("expectedHandle")&&!targetHandle.equals(p.get("expectedHandle").getAsString())) throw new IllegalArgumentException("stale_entity: handle changed");
                if((combat||kind.equals("attack_entity"))&&target instanceof EntityPlayer&&!Json.bool(p,"allowPlayers",false))throw new IllegalArgumentException("allowPlayers required to attack a player");
            }
            if(kind.equals("eat")) {
                ItemStack held=player.getHeldItem();
                if(held==null||!Set.of(EnumAction.eat,EnumAction.drink).contains(held.getItemUseAction())) throw new IllegalArgumentException("select an edible/drinkable held stack first");
            }
            if(Json.bool(p,"pursue",false)) throw new IllegalArgumentException("combat is stationary; compose guarded navigation with combat using entity observations");
            before=snapshot();
        }
        void start() {
            ControlRegistry.controls().focusForInput();
            lease=ControlRegistry.controls().arbiter().acquire("interaction:"+kind,reason->{
                // Native use may synchronously open a screen. The click still needs an honest receipt.
                if(delivering) {if(!reason.equals("gui_open"))revokedDuringDelivery=reason;}
                else finish("cancelled",reason);
            },Json.bool(p,"overrideProtection",false));
            if(kind.equals("select_hotbar")) {
                int slot=Json.integer(p,"slot",-1,0,8);
                if(p.has("expected")&&!Stacks.expected(player.inventory.mainInventory[slot],p.get("expected"))) throw new IllegalArgumentException("stale slot stack");
                player.inventory.currentItem=slot;mc.playerController.updateController();accepted=true;
                delivered=true;deliveredAt=elapsed;lease.close();return; // settles like the other actions; tick() confirms the slot is still selected
            }
            if(sneak) lease.setKeys(Set.of(mc.gameSettings.keyBindSneak.getKeyCode()));
            if(target!=null) aimEntity();else if(point!=null) aim(point);
        }
        /** Block-local centre of one face of the target's selection box: where a native click on that face lands. */
        double[] facePoint(int face) {
            double[] hit={.5,.5,.5};int axis=face/2;axis=axis==0?1:axis==1?2:0;hit[axis]=face%2;
            if(fluidTarget)return hit;
            Block block=world.getBlock(x,y,z);block.setBlockBoundsBasedOnState(world,x,y,z);
            var box=dev.modbench.api.ControlRegistry.targeting().withContext(()->block.getSelectedBoundingBoxFromPool(world,x,y,z));
            if(box!=null) {
                double[] low={box.minX-x,box.minY-y,box.minZ-z},high={box.maxX-x,box.maxY-y,box.maxZ-z};
                for(int i=0;i<3;i++)hit[i]=(low[i]+high[i])/2;
                hit[axis]=face%2==0?low[axis]:high[axis];
            }
            return hit;
        }
        /** No face given: the one a player standing here would click, top first. Top when none is in sight, so delivery reports it. */
        int visibleFace() {
            for(int f:new int[]{1,2,3,4,5,0}) {
                double[] h=facePoint(f);Vec3 eye=eyes();
                double dx=x+h[0]-eye.xCoord,dy=y+h[1]-eye.yCoord,dz=z+h[2]-eye.zCoord,far=1+.05/Math.max(.05,Math.sqrt(dx*dx+dy*dy+dz*dz));
                MovingObjectPosition m=world.rayTraceBlocks(eye,Vec3.createVectorHelper(eye.xCoord+dx*far,eye.yCoord+dy*far,eye.zCoord+dz*far));
                if(m!=null&&m.typeOfHit==MovingObjectPosition.MovingObjectType.BLOCK&&m.blockX==x&&m.blockY==y&&m.blockZ==z&&m.sideHit==f)return f;
            }
            return 1;
        }
        // Forge 1.7's local player eyeHeight is an offset from its stance, not feet.
        // The native override includes that offset and matches Item's own ray.
        Vec3 eyes() {return player.getPosition(1);}
        void aim(Vec3 to) {
            Vec3 from=eyes();double dx=to.xCoord-from.xCoord,dy=to.yCoord-from.yCoord,dz=to.zCoord-from.zCoord;
            lease.look((float)(Math.toDegrees(Math.atan2(dz,dx))-90),(float)-Math.toDegrees(Math.atan2(dy,Math.sqrt(dx*dx+dz*dz))));
        }
        void aimEntity() {
            var box=target.boundingBox;Vec3 eye=eyes();double margin=.05;
            point=Vec3.createVectorHelper(Math.max(box.minX+margin,Math.min(box.maxX-margin,eye.xCoord)),Math.max(box.minY+margin,Math.min(box.maxY-margin,eye.yCoord)),Math.max(box.minZ+margin,Math.min(box.maxZ-margin,eye.zCoord)));
            aim(point);
        }
        void checkHeld() {if(p.has("expectedHeld")&&!Stacks.expected(player.getHeldItem(),p.get("expectedHeld")))throw new IllegalArgumentException("stale_held: changed before use");}
        void checkBlock() {
            if(p.has("expected")) {JsonObject expected=p.getAsJsonObject("expected");
                if(expected.has("id")&&!Json.string(expected,"id","").equals(Block.blockRegistry.getNameForObject(world.getBlock(x,y,z)))||expected.has("meta")&&expected.get("meta").getAsInt()!=world.getBlockMetadata(x,y,z)) throw new IllegalArgumentException("stale_block: identity changed");}
        }
        MovingObjectPosition ray() {
            if(fluidTarget) {
                Vec3 eye=eyes(),look=player.getLook(1);double reach=mc.playerController.getBlockReachDistance();
                return world.rayTraceBlocks(eye,eye.addVector(look.xCoord*reach,look.yCoord*reach,look.zCoord*reach),true);
            }
            return (MovingObjectPosition)dev.modbench.api.ControlRegistry.targeting().refresh();
        }
        boolean entityInReach() {
            if(target==null||target.isDead||world.getEntityByID(target.getEntityId())!=target) return false;
            if(eyes().distanceTo(point)>range||!player.canEntityBeSeen(target))return false;
            MovingObjectPosition ray=ray();return ray!=null&&ray.entityHit==target;
        }
        void tick() {
            if(finished)return;elapsed++;
            if(player.getHealth()<=0||player.isDead){finish("cancelled","player died");return;}
            if(combat) {combatTick();return;}
            if(!delivered) {
                // Rotation/sneak chosen before a full client tick: server pose arrives before item-use packet.
                checkHeld();if(blockTarget)checkBlock();
                if(sneak&&!player.isSneaking())return;
                // Sneaking and residual movement change the native eye position.
                // Re-aim from the actual stance before ray validation and delivery.
                if(target!=null)aimEntity();else if(point!=null)aim(point);
                player.sendMotionUpdates();
                delivering=true;
                try {
                    if(blockTarget) {
                        MovingObjectPosition hit=ray();
                        if(hit==null||hit.typeOfHit!=MovingObjectPosition.MovingObjectType.BLOCK||hit.blockX!=x||hit.blockY!=y||hit.blockZ!=z||kind.equals("use_block")&&hit.sideHit!=face)throw new IllegalArgumentException("target_not_visible: reach, obstruction or face changed");
                        if(kind.equals("use_block")) {
                            if(hit.hitVec.distanceTo(point)>.03)throw new IllegalArgumentException("target_not_visible: requested hit point is not on the visible surface");
                            accepted=mc.playerController.onPlayerRightClick(player,world,player.getHeldItem(),x,y,z,face,hit.hitVec);if(accepted)player.swingItem();
                        }
                        else useItem();
                    } else if(target!=null) {
                        if(!entityInReach())throw new IllegalArgumentException("target_not_visible: entity lost, obstructed or out of reach");
                        if(kind.equals("attack_entity")){mc.playerController.attackEntity(player,target);player.swingItem();accepted=true;attacks++;}
                        else {accepted=mc.playerController.interactWithEntitySendPacket(player,target);if(accepted)player.swingItem();}
                    } else useItem();
                    delivered=true;deliveredAt=elapsed;
                } finally {delivering=false;}
                if(revokedDuringDelivery!=null){finish("cancelled",revokedDuringDelivery);return;}
                if(!ownUse&&lease!=null)lease.close();
            }
            if(ownUse) {
                if(!player.isUsingItem()||elapsed>=duration) {
                    stopUse();
                    deliveredAt=elapsed;
                }
            } else if(elapsed-deliveredAt>=settle) {
                if(kind.equals("eat")) {
                    JsonElement held=Json.GSON.toJsonTree(Stacks.json(player.getHeldItem()));
                    boolean changed=!held.equals(before.get("held"))||player.getFoodStats().getFoodLevel()!=before.get("food").getAsInt();
                    if(!changed){finish("failed","no consumption observed");return;}
                }
                if(kind.equals("select_hotbar")&&player.inventory.currentItem!=Json.integer(p,"slot",-1,0,8)){finish("failed","selection changed while settling: another job (mining picks its own tool) or the game moved it");return;}
                finish("completed",null);
            }
        }
        void useItem() {
            if(player.getHeldItem()==null)throw new IllegalArgumentException("held item required");
            // Match vanilla's air-use event and key state before invoking the item.
            // Pack hooks may veto or initialize use here; sendUseItem alone skips them.
            Set<Integer> keys=new LinkedHashSet<>();keys.add(mc.gameSettings.keyBindUseItem.getKeyCode());if(sneak)keys.add(mc.gameSettings.keyBindSneak.getKeyCode());lease.setKeys(keys);
            if(net.minecraftforge.event.ForgeEventFactory.onPlayerInteract(player,
                net.minecraftforge.event.entity.player.PlayerInteractEvent.Action.RIGHT_CLICK_AIR,
                0,0,0,-1,world).isCanceled())throw new IllegalArgumentException("native item-use event cancelled");
            accepted=mc.playerController.sendUseItem(player,world,player.getHeldItem());ownUse=player.isUsingItem();
            nativeUseTicks=ownUse?player.getItemInUseCount():0;
            if(kind.equals("eat")&&ownUse&&nativeUseTicks>duration-elapsed)
                throw new IllegalArgumentException("native_use_duration_exceeds_budget: item requires "+nativeUseTicks+" ticks, remaining budget "+(duration-elapsed)+"; choose another food or explicitly increase ticks");
            if(!ownUse&&kind.equals("eat"))throw new IllegalArgumentException("item did not start consumption: food="+player.getFoodStats().getFoodLevel()+", invulnerable="+player.capabilities.disableDamage+", canEat="+player.canEat(false)+"; native item rules apply");
        }
        void combatTick() {
            if(mc.currentScreen!=null){finish("cancelled","gui_changed");return;}
            if(elapsed>=duration){outcome="duration_elapsed";finish("completed",null);return;}
            if(target!=null) {
                if(target instanceof EntityLivingBase living&&living.getHealth()<=0){deaths++;target=null;}
                else if(target.isDead||world.getEntityByID(target.getEntityId())!=target) {
                    if(p.has("entityId")){outcome="target_lost";finish("completed",null);return;}target=null;
                }
            }
            if(target==null) {
                if(p.has("entityId")){outcome="target_dead_observed";finish("completed",null);return;}
                double best=range*range;
                for(Object value:world.loadedEntityList) {
                    Entity e=(Entity)value;
                    if(e==player||e.isDead||!(e instanceof EntityLivingBase living)||living.getHealth()<=0||e instanceof EntityPlayer&&!Json.bool(p,"allowPlayers",false))continue;
                    if(Json.bool(p,"hostile",true)&&!hostile(e,player))continue;
                    if(p.has("types")){boolean match=false;for(JsonElement type:p.getAsJsonArray("types"))if(type.getAsString().equals(EntityList.getEntityString(e)))match=true;if(!match)continue;}
                    double distance=e.getDistanceSqToEntity(player);if(distance<best&&player.canEntityBeSeen(e)){best=distance;target=e;}
                }
                if(target==null){if(Json.bool(p,"stopWhenClear",true)){outcome="clear";finish("completed",null);}return;}
                targetHandle=handle(target);aimEntity();return;
            }
            if(elapsed-lastAttack>=interval&&entityInReach()) {
                mc.playerController.attackEntity(player,target);player.swingItem();attacks++;accepted=true;lastAttack=elapsed;
            }
            aimEntity();
        }
        JsonObject snapshot() {
            if(mc.theWorld!=world||mc.thePlayer!=player) return Json.object("available",false,"reason","world/player changed");
            return Json.object("held",Stacks.json(player.getHeldItem()),"selectedSlot",player.inventory.currentItem,"food",player.getFoodStats().getFoodLevel(),
                "health",player.getHealth(),"usingItem",player.isUsingItem(),"canEat",player.canEat(false),"invulnerable",player.capabilities.disableDamage,"gui",mc.currentScreen==null?null:mc.currentScreen.getClass().getName(),
                "block",blockTarget?Json.object("id",Block.blockRegistry.getNameForObject(world.getBlock(x,y,z)),"meta",world.getBlockMetadata(x,y,z)):null,
                "target",target==null?null:entity(target));
        }
        JsonObject receipt(String state,String error) {
            JsonObject after=snapshot();JsonArray changes=new JsonArray();
            for(String field:List.of("held","selectedSlot","food","health","usingItem","gui","block","target"))
                if(!Objects.equals(before.get(field),after.get(field)))changes.add(new JsonPrimitive(field));
            // sendUseItem's boolean only tracks stack reference/count changes;
            // in-place NBT changes may return false despite a successful use.
            JsonObject out=Json.object("state",state,"action",kind,"outcome",outcome,"error",error,"elapsedTicks",elapsed,
                "nativeReturn",accepted,"serverAcknowledged",consumptionAcknowledged,"nativeUseTicks",nativeUseTicks,"tickBudget",duration,"before",before,"after",after,"observedChanges",changes,"attackAttempts",attacks,"observedDeaths",deaths);
            ControlRegistry.memory().refusedSince(refusalMark).forEach((k,v)->out.add(k,Json.GSON.toJsonTree(v)));
            return out;
        }
        void stopUse() {
            if(ownUse) {ownUse=false;if(mc.thePlayer==player&&player.isUsingItem())mc.playerController.onStoppedUsingItem(player);}
            if(lease!=null)lease.close();
        }
        void finish(String state,String reason) {
            if(finished)return;finished=true;
            try {stopUse();}finally {if(lease!=null)lease.close();if(active==this)active=null;}
            last=receipt(state,reason);
            if(state.equals("completed"))r.reply(last);else r.fail(state,reason,last);
        }
    }
    /** Some modded mobs report NaN health; JSON has no NaN, and one such mob must not blind the whole observation. */
    private static float finite(float value){return Float.isNaN(value)||Float.isInfinite(value)?0:value;}
}
