// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh;

import baritone.Baritone;
import dev.modbench.api.ControlRegistry;
import dev.modbench.api.InputArbiter;
import dev.modbench.api.Navigation;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.monster.EntityCreeper;
import net.minecraft.entity.monster.IMob;
import java.util.*;
import static baritone.gtnh.pathing.WorkSpec.*;

/**
 * One fight, at the level mining is one job: the caller names the mob and the limits, this does the footwork.
 * Out of reach the source FollowProcess paths to the target; in reach the engine rests and this aims, blocks with a
 * sword between swings and lands each swing while falling (a critical hit). It ends, as a failure so that the
 * actionFailed guard pauses the world, as soon as the fight is no longer the one that was asked for.
 */
final class FightJob implements Navigation.Job {
    private static final double REACH=3;
    private final Minecraft mc=Minecraft.getMinecraft();
    private final Baritone engine;
    private final Object world=mc.theWorld,player=mc.thePlayer;
    private final String scope=ControlRegistry.memory().memory().scope();
    private final Map<baritone.api.Settings.Setting<?>,Object> saved=new LinkedHashMap<>();
    private final InputArbiter.Lease lease;
    private final Integer chosen;
    private final boolean hold,crit,block;
    private final int duration,interval,maxAttackers;
    private final double leash,bailHealth,ax,ay,az;
    private String state="fighting",reason="",phase="starting";
    private Entity target;
    private int ticks,lastAttack=-100,lastUseful,attacks,crits,kills,clearTicks;

    FightJob(Baritone engine,Map<String,Object> params){
        this.engine=engine;
        hold=bool(params,"hold",false);crit=bool(params,"crit",true);block=bool(params,"block",true);
        chosen=params.containsKey("entityId")?integer(params,"entityId",0,Integer.MIN_VALUE,Integer.MAX_VALUE):null;
        if(chosen==null&&!hold)throw new IllegalArgumentException("fight needs entityId (from obs.entities or the clock's threats), or hold:true to stand and hit whatever hostile comes into reach");
        duration=integer(params,"durationTicks",600,1,6000);interval=integer(params,"intervalTicks",10,10,40);
        maxAttackers=integer(params,"maxAttackers",2,1,8);leash=number(params,"leash",16,2,48);bailHealth=number(params,"bailHealth",8,0,40);
        var me=mc.thePlayer;ax=me.posX;ay=me.boundingBox.minY;az=me.posZ;
        if(params.containsKey("weaponSlot")){me.inventory.currentItem=integer(params,"weaponSlot",0,0,8);mc.playerController.updateController();}
        var settings=Baritone.settings();
        for(var s:List.of(settings.allowBreak,settings.allowPlace,settings.followRadius,settings.followOffsetDistance))saved.put(s,s.value);
        engine.getPathingBehavior().forceCancel();
        lease=ControlRegistry.controls().arbiter().acquire("baritone-fight",this::cancel,false,true);
        settings.allowBreak.value=false;settings.allowPlace.value=false;settings.followRadius.value=2;settings.followOffsetDistance.value=0d;
        engine.overrideProtection=false;engine.positionAllowed=p->true;engine.explicitMiningTargets=()->s->false;
        engine.getInputOverrideHandler().attach(lease);
        if(!hold)engine.getFollowProcess().follow(e->e.getEntityId()==chosen);
    }
    void tick(){
        if(done())return;
        var me=mc.thePlayer;
        if(mc.theWorld!=world||me!=player||!scope.equals(ControlRegistry.memory().memory().scope())){cancel("world_changed");return;}
        if(!lease.isActive()){cancel("control_lost");return;}
        if(mc.currentScreen!=null){cancel("gui_open");return;}
        if(me.isDead||me.getHealth()<=0){cancel("player_unavailable");return;}
        if(ticks++>=duration){finish("failed","duration_elapsed");return;}
        if(me.getHealth()<=bailHealth){finish("failed","health_at_bail_line");return;}
        List<Entity> near=hostiles(4);
        if(near.size()>maxAttackers){finish("failed","outnumbered: "+near.size()+" hostile mobs within 4 blocks");return;}
        for(Entity e:hostiles(7))if(e!=target&&e instanceof EntityCreeper creeper&&creeper.getCreeperState()>0){finish("failed","creeper_swelling: entity "+e.getEntityId());return;}

        if(target!=null&&(target.isDead||((EntityLivingBase)target).getHealth()<=0)){kills++;target=null;if(chosen!=null){finish("succeeded","target_dead");return;}}
        if(chosen!=null){
            target=mc.theWorld.getEntityByID(chosen);
            if(!(target instanceof EntityLivingBase)||target.isDead){target=null;finish("failed",attacks>0?"target_lost":"no_such_entity");return;}
            double dx=target.posX-ax,dy=target.boundingBox.minY-ay,dz=target.posZ-az;
            if(!hold&&Math.sqrt(dx*dx+dy*dy+dz*dz)>leash){finish("failed","target_beyond_leash");return;}
        } else {
            List<Entity> all=hostiles(8);target=all.isEmpty()?null:all.get(0);
            if(target==null){phase="clear";rest(Set.of());if(++clearTicks>=40)finish("succeeded","clear");return;}
            clearTicks=0;
        }
        if(ticks-lastUseful>200){finish("failed","cannot_reach_target");return;}

        boolean inReach=reach(target)<=REACH&&me.canEntityBeSeen(target);
        if(!inReach&&!hold){phase="pursuing";engine.tickStart();return;}
        Set<Integer> keys=new LinkedHashSet<>();var game=mc.gameSettings;
        aim(target);
        if(target instanceof EntityCreeper creeper&&creeper.getCreeperState()>0){phase="backing_off";keys.add(game.keyBindBack.getKeyCode());rest(keys);lastUseful=ticks;return;}
        phase=inReach?"striking":"holding";
        var held=me.getHeldItem();
        if(block&&held!=null&&held.getItemUseAction()==net.minecraft.item.EnumAction.block)keys.add(game.keyBindUseItem.getKeyCode());
        int since=ticks-lastAttack;
        boolean falling=!me.onGround&&me.fallDistance>0&&!me.isInWater()&&!me.isOnLadder();
        if(crit&&inReach&&me.onGround&&since>=interval-6)keys.add(game.keyBindJump.getKeyCode());
        if(inReach&&since>=interval&&(!crit||falling||since>=interval+10)){
            mc.playerController.attackEntity(me,target);me.swingItem();attacks++;if(falling)crits++;lastAttack=lastUseful=ticks;
        }
        rest(keys);
    }
    /** The engine does not tick here, so its keys and any block it was breaking are let go and ours are published. */
    private void rest(Set<Integer> keys){
        var input=engine.getInputOverrideHandler();input.clearAllKeys();input.getBlockBreakHelper().stopBreakingBlock();
        lease.setKeys(keys);lease.look(mc.thePlayer.rotationYaw,mc.thePlayer.rotationPitch);
    }
    private void aim(Entity e){
        var me=mc.thePlayer;
        double dx=e.posX-me.posX,dz=e.posZ-me.posZ,dy=e.boundingBox.minY+e.height*.6-(me.boundingBox.minY+me.getEyeHeight());
        me.rotationYaw=(float)(Math.toDegrees(Math.atan2(dz,dx))-90);me.rotationPitch=(float)-Math.toDegrees(Math.atan2(dy,Math.sqrt(dx*dx+dz*dz)));
    }
    /** Eye to the nearest point of the mob's box, which is what the server measures a hit by. */
    private double reach(Entity e){
        var me=mc.thePlayer;var b=e.boundingBox;double ex=me.posX,ey=me.boundingBox.minY+me.getEyeHeight(),ez=me.posZ;
        double dx=Math.max(Math.max(b.minX-ex,0),ex-b.maxX),dy=Math.max(Math.max(b.minY-ey,0),ey-b.maxY),dz=Math.max(Math.max(b.minZ-ez,0),ez-b.maxZ);
        return Math.sqrt(dx*dx+dy*dy+dz*dz);
    }
    /** Living hostile mobs the player can see within `radius`, nearest first. */
    private List<Entity> hostiles(double radius){
        var me=mc.thePlayer;List<Entity> out=new ArrayList<>();
        for(Object value:mc.theWorld.loadedEntityList)
            if(value instanceof IMob&&value instanceof EntityLivingBase e&&!e.isDead&&e.getHealth()>0&&e.getDistanceToEntity(me)<=radius&&me.canEntityBeSeen(e))out.add(e);
        out.sort(Comparator.comparingDouble(e->e.getDistanceSqToEntity(me)));return out;
    }
    private void finish(String state,String reason){
        if(done())return;this.state=state;this.reason=reason;
        engine.getPathingBehavior().forceCancel();engine.getInputOverrideHandler().release();
        saved.forEach(ReferenceSettings::copy);if(lease!=null)lease.close();
    }
    @Override public void cancel(String reason){finish("cancelled",reason);}
    @Override public boolean done(){return !state.equals("fighting");}
    @Override public boolean succeeded(){return state.equals("succeeded");}
    @Override public Map<String,Object> status(){
        var out=new LinkedHashMap<String,Object>();var me=mc.thePlayer;
        out.put("action","fight");out.put("state",state);out.put("reason",reason);out.put("phase",phase);out.put("ticks",ticks);
        out.put("attacks",attacks);out.put("criticalHits",crits);out.put("kills",kills);
        if(me==player){
            out.put("health",me.getHealth());
            out.put("target",target instanceof EntityLivingBase t?Map.of("entityId",t.getEntityId(),"type",String.valueOf(net.minecraft.entity.EntityList.getEntityString(t)),"health",t.getHealth(),"distance",Math.round(t.getDistanceToEntity(me)*10)/10.0):null);
            // Whoever is left is the next decision: the same rows obs.entities gives, so nothing here is new knowledge.
            out.put("hostilesInSight",hostiles(16).stream().map(e->Map.of("entityId",e.getEntityId(),"type",String.valueOf(net.minecraft.entity.EntityList.getEntityString(e)),"distance",Math.round(e.getDistanceToEntity(me)*10)/10.0)).toList());
        }
        out.put("controlOwned",!done()&&lease.isActive());out.put("scope",scope);return out;
    }
}
