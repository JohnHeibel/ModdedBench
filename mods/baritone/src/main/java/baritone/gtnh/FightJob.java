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
import java.util.*;
import java.util.function.Predicate;
import static baritone.gtnh.pathing.WorkSpec.*;

/**
 * One fight, at the level mining is one job: the caller names the target and the limits, this does the footwork.
 * The target is whatever entity the caller names (entityId, or a selector); hostility is a rule the caller may replace,
 * used only for hold's default target, the outnumbered guard and the report.
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
    private final Map<String,Object> jobSettings=new LinkedHashMap<>();
    private final InputArbiter.Lease lease;
    private final Integer chosen;
    private final Predicate<Entity> named,hostile;
    private final List<Map<String,Object>> hostileRule;
    private final boolean hold,crit,block;
    private final int duration,interval,maxAttackers;
    private final double leash,bailHealth,ax,ay,az;
    private String state="fighting",reason="",phase="starting";
    private Entity target;
    private int ticks,lastAttack=-100,lastUseful,attacks,crits,kills,clearTicks;
    // Ranged: nothing here knows a weapon. How it is used is found by trying (hold and release; if nothing flies, click); how
    // its projectile flies comes in as numbers, and each shot's velocity samples go back out for the caller to fit them from.
    private boolean ranged;
    private Integer meleeSlot;
    private float lastHealth=-1;
    private double minRange=6,maxRange=20,speed=3,gravity=.05,drag=.99;
    private int drawTicks=20,reloadTicks=5,shotPhase,phaseTicks,shots,hits,duds;
    private boolean clickAfterLoad;
    private String weaponKey="";
    private final Set<Integer> seenEntities=new HashSet<>();
    private final Set<String> shotKinds=new LinkedHashSet<>();
    private final List<Map<String,Object>> adjustments=new ArrayList<>();
    private Entity tracked;
    private List<double[]> track=new ArrayList<>();
    private final List<List<double[]>> tracks=new ArrayList<>();


    FightJob(Baritone engine,Map<String,Object> params){
        this.engine=engine;
        hold=bool(params,"hold",false);crit=bool(params,"crit",true);block=bool(params,"block",true);
        chosen=params.containsKey("entityId")?integer(params,"entityId",0,Integer.MIN_VALUE,Integer.MAX_VALUE):null;
        named=chosen!=null?e->e.getEntityId()==chosen:params.containsKey("target")?ReferenceFollowJob.selector(child(params,"target")):null;
        hostileRule=params.containsKey("hostile")?list(params.get("hostile")).stream().map(o->object(o)).toList():List.of(Map.of("class","net.minecraft.entity.monster.IMob"));
        var rules=hostileRule.stream().map(ReferenceFollowJob::selector).toList();hostile=e->rules.stream().anyMatch(r->r.test(e));
        if(named==null&&!hold)throw new IllegalArgumentException("fight needs entityId (from obs.entities or the clock's threats) or a target selector {entityId|uuid|type|name|class}, or hold:true to stand and hit whatever the hostile rule matches");
        duration=integer(params,"durationTicks",600,1,6000);interval=integer(params,"intervalTicks",10,10,40);
        maxAttackers=integer(params,"maxAttackers",2,1,8);leash=number(params,"leash",16,2,48);bailHealth=number(params,"bailHealth",8,0,40);
        var me=mc.thePlayer;ax=me.posX;ay=me.boundingBox.minY;az=me.posZ;
        if(params.containsKey("weaponSlot")){me.inventory.currentItem=integer(params,"weaponSlot",0,0,8);mc.playerController.updateController();}
        ranged=params.containsKey("ranged");
        if(ranged){
            var held=me.getHeldItem();if(held==null)throw new IllegalArgumentException("ranged fight needs the weapon in hand: pass weaponSlot");
            weaponKey=net.minecraft.item.Item.itemRegistry.getNameForObject(held.getItem())+"|"+held.getDisplayName();
            var asked=child(params,"ranged");
            speed=number(asked,"speed",3,.1,20);gravity=number(asked,"gravity",.05,0,1);drag=number(asked,"drag",.99,.5,1);
            drawTicks=integer(asked,"drawTicks",20,1,200);reloadTicks=integer(asked,"reloadTicks",5,0,400);clickAfterLoad=bool(asked,"clickAfterLoad",false);
            if(asked.containsKey("meleeSlot"))meleeSlot=integer(asked,"meleeSlot",0,0,8);
            minRange=number(asked,"minRange",6,0,32);maxRange=number(asked,"maxRange",20,4,48);
            for(Object o:mc.theWorld.loadedEntityList)seenEntities.add(((Entity)o).getEntityId());
        }
        var settings=Baritone.settings();
        for(var s:List.of(settings.allowBreak,settings.allowPlace,settings.followRadius,settings.followOffsetDistance))saved.put(s,s.value);
        engine.getPathingBehavior().forceCancel();
        lease=ControlRegistry.controls().arbiter().acquire("baritone-fight",this::cancel,false,true);
        // For this job only, shown in the receipt and restored when it ends.
        settings.allowBreak.value=bool(params,"allowBreak",false);settings.allowPlace.value=bool(params,"allowPlace",false);settings.followRadius.value=2;settings.followOffsetDistance.value=0d;
        for(var s:saved.keySet())jobSettings.put(s.getName(),s.value);jobSettings.put("overrideProtection",false);
        engine.overrideProtection=false;engine.positionAllowed=p->true;engine.explicitMiningTargets=()->s->false;
        engine.getInputOverrideHandler().attach(lease);
        if(!hold)engine.getFollowProcess().follow(e->e==target);
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
        if(near.size()>maxAttackers){finish("failed","outnumbered: "+near.size()+" entities of the hostile rule within 4 blocks");return;}
        for(Entity e:matching(x->x instanceof EntityCreeper,7))if(e!=target&&((EntityCreeper)e).getCreeperState()>0){finish("failed","creeper_swelling: entity "+e.getEntityId());return;}

        if(target!=null&&dead(target)){kills++;target=null;if(chosen!=null){finish("succeeded","target_dead");return;}}
        if(chosen!=null){
            target=mc.theWorld.getEntityByID(chosen);
            if(target==null||dead(target)){target=null;finish("failed",attacks>0?"target_lost":"no_such_entity");return;}
        } else {
            // A selector (or, holding, the hostile rule) keeps its target while it lives, else takes the nearest match in sight.
            if(target==null){List<Entity> all=matching(named!=null?named:hostile,hold?8:leash);target=all.isEmpty()?null:all.get(0);}
            if(target==null){phase="clear";rest(Set.of());if(++clearTicks>=40)finish("succeeded","clear");return;}
            clearTicks=0;
        }
        double tx=target.posX-ax,ty=target.boundingBox.minY-ay,tz=target.posZ-az;
        if(!hold&&Math.sqrt(tx*tx+ty*ty+tz*tz)>leash){finish("failed","target_beyond_leash");return;}
        if(ticks-lastUseful>200){finish("failed","cannot_reach_target");return;}

        if(ranged&&shotPhase!=1&&shotPhase!=2&&!hostiles(3.5).isEmpty()){ // a mob walks faster than a player backs away: a launcher is no use at arm's length
            if(meleeSlot==null){finish("failed","hostile_in_melee_range: the ranged fight is over, "+shots+" shots; fight on with a melee weapon (or pass ranged.meleeSlot) or leave");return;}
            ranged=false;me.inventory.currentItem=meleeSlot;mc.playerController.updateController();rest(Set.of());
            if(chosen==null||reach(target)>REACH)target=hostiles(3.5).get(0);
        }
        if(ranged){rangedTick(target);return;}
        boolean inReach=reach(target)<=REACH&&me.canEntityBeSeen(target);
        if(!inReach&&!hold){phase="pursuing";engine.tickStart();return;}
        Set<Integer> keys=new LinkedHashSet<>();var game=mc.gameSettings;
        aim(target);
        if(target instanceof EntityCreeper creeper&&creeper.getCreeperState()>0){phase="backing_off";keys.add(game.keyBindBack.getKeyCode());rest(keys);lastUseful=ticks;return;}
        phase=inReach?"striking":"holding";
        var held=me.getHeldItem();
        if(block&&held!=null&&held.getItemUseAction()==net.minecraft.item.EnumAction.block){
            keys.add(game.keyBindUseItem.getKeyCode());
            if(!me.isUsingItem())mc.playerController.sendUseItem(me,mc.theWorld,held); // a held key keeps the block up; it does not raise it
        }
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
    /** Eye to the nearest point of the target's box, which is what the server measures a hit by. */
    private double reach(Entity e){
        var me=mc.thePlayer;var b=e.boundingBox;double ex=me.posX,ey=me.boundingBox.minY+me.getEyeHeight(),ez=me.posZ;
        double dx=Math.max(Math.max(b.minX-ex,0),ex-b.maxX),dy=Math.max(Math.max(b.minY-ey,0),ey-b.maxY),dz=Math.max(Math.max(b.minZ-ez,0),ez-b.maxZ);
        return Math.sqrt(dx*dx+dy*dy+dz*dz);
    }
    private static boolean dead(Entity e){return e.isDead||e instanceof EntityLivingBase l&&l.getHealth()<=0;}
    /** Entities of the hostile rule the player can see within `radius`, nearest first. */
    private List<Entity> hostiles(double radius){return matching(hostile,radius);}
    private List<Entity> matching(Predicate<Entity> rule,double radius){
        var me=mc.thePlayer;List<Entity> out=new ArrayList<>();
        for(Object value:mc.theWorld.loadedEntityList)
            if(value instanceof Entity e&&e!=me&&!dead(e)&&rule.test(e)&&e.getDistanceToEntity(me)<=radius&&me.canEntityBeSeen(e))out.add(e);
        out.sort(Comparator.comparingDouble(e->e.getDistanceSqToEntity(me)));return out;
    }
    private void rangedTick(Entity t){
        var me=mc.thePlayer;var game=mc.gameSettings;int use=game.keyBindUseItem.getKeyCode();
        watchShot();
        float health=t instanceof EntityLivingBase l?l.getHealth():-1;
        if(lastHealth>=0&&health>=0&&health<lastHealth&&shots>0)hits++;
        lastHealth=health;
        double distance=me.getDistanceToEntity(t);boolean sight=me.canEntityBeSeen(t);
        if((!sight||distance>maxRange)&&shotPhase==0&&!hold){phase="closing";phaseTicks=0;engine.tickStart();return;}
        Set<Integer> keys=new LinkedHashSet<>();
        if(sight)aimBallistic(t);
        if(distance<minRange&&clearBehind())keys.add(game.keyBindBack.getKeyCode());
        phaseTicks++;
        switch(shotPhase){
            case 0->{
                if(!sight){phaseTicks=0;phase="no_line_of_sight";break;}
                phase="drawing";keys.add(use);
                if(phaseTicks==1)mc.playerController.sendUseItem(me,mc.theWorld,me.getHeldItem());
                if(phaseTicks>=drawTicks){shotPhase=1;phaseTicks=0;} // the use key is left out from the next tick on: that is the release
            }
            case 1->{
                phase="released";
                if(clickAfterLoad&&phaseTicks>=3){shotPhase=2;phaseTicks=0;}
                else if(phaseTicks>=10){if(clickAfterLoad)dud();else{clickAfterLoad=true;shotPhase=2;phaseTicks=0;adjust("clickAfterLoad",false,true,"nothing flew on release; perhaps it only loaded");}}
            }
            case 2->{
                phase="firing";
                if(phaseTicks==1)mc.playerController.sendUseItem(me,mc.theWorld,me.getHeldItem());
                if(phaseTicks<=2)keys.add(use);else if(phaseTicks>=12)dud();
            }
            default->{phase="reloading";if(phaseTicks>=reloadTicks){shotPhase=0;phaseTicks=0;}}
        }
        rest(keys);
    }
    /** A change this job made to the numbers it was given, for the receipt. */
    private void adjust(String key,Object from,Object to,String why){if(adjustments.size()<8)adjustments.add(Map.of(key,List.of(from,to),"why",why));}
    /** Neither releasing nor clicking sent anything: wind longer next time; three in a row is no ammunition, or a weapon this cannot work. */
    private void dud(){
        duds++;int before=drawTicks;drawTicks=Math.min(100,drawTicks+10);shotPhase=0;phaseTicks=0;
        adjust("drawTicks",before,drawTicks,"nothing flew: wind longer next time, at most 100 ticks");
        if(duds>=3)finish("failed","no_projectile_fired: out of ammunition, or this weapon is not used by holding, releasing or clicking");
    }
    /** Whatever new entity that is not a living one appears beside the player just after a release or click, flying away
     *  from it, is this shot, of whatever class a mod gives it (the receipt names it); follow it to learn how the weapon flies. */
    private void watchShot(){
        var me=mc.thePlayer;
        for(Object o:mc.theWorld.loadedEntityList){
            Entity e=(Entity)o;
            if(e instanceof EntityLivingBase||!seenEntities.add(e.getEntityId()))continue;
            double away=e.motionX*(e.posX-me.posX)+e.motionY*(e.posY-me.posY)+e.motionZ*(e.posZ-me.posZ);
            if((shotPhase==1||shotPhase==2)&&tracked==null&&e.getDistanceToEntity(me)<4&&away>0&&e.motionX*e.motionX+e.motionY*e.motionY+e.motionZ*e.motionZ>.09){
                if(clickAfterLoad!=(shotPhase==2))adjust("clickAfterLoad",clickAfterLoad,shotPhase==2,"the shot flew after "+(shotPhase==2?"a click":"a release"));
                tracked=e;shots++;duds=0;clickAfterLoad=shotPhase==2;shotPhase=3;phaseTicks=0;lastUseful=ticks;shotKinds.add(e.getClass().getName());
            }
        }
        if(tracked==null)return;
        // The projectile's own velocity, which its client copy steps with the weapon's real constants; positions jitter with every server correction.
        double[] last=track.isEmpty()?null:track.get(track.size()-1);
        double h=Math.sqrt(tracked.motionX*tracked.motionX+tracked.motionZ*tracked.motionZ);
        boolean stopped=tracked.isDead||h<.05||last!=null&&h>last[0]*1.02; // landed, or knocked about by something
        if(!stopped)track.add(new double[]{h,tracked.motionY});
        if(stopped||track.size()>=8){if(track.size()>=3)tracks.add(track);track=new ArrayList<>();tracked=null;}
    }
    /** Height reached, and ticks taken, when a shot launched at `angle` has covered `far` blocks of ground. */
    private double[] fly(double angle,double far){
        double x=0,y=0,vx=speed*Math.cos(angle),vy=speed*Math.sin(angle);int t=0;
        while(x<far&&t<200&&vx>.01){x+=vx;y+=vy;vx*=drag;vy=vy*drag-gravity;t++;}
        return new double[]{x<far?-1e9:y,t};
    }
    private void aimBallistic(Entity t){
        var me=mc.thePlayer;double ex=me.posX,ey=me.boundingBox.minY+me.getEyeHeight()-.1,ez=me.posZ,flight=0,angle=0,dx=0,dz=0;
        for(int pass=0;pass<2;pass++){ // second pass leads the target by where it will be when the shot arrives
            dx=t.posX+(t.posX-t.prevPosX)*flight-ex;dz=t.posZ+(t.posZ-t.prevPosZ)*flight-ez;
            double far=Math.sqrt(dx*dx+dz*dz),up=t.boundingBox.minY+t.height*.6-ey,lo=-Math.PI/2+.01,hi=Math.PI/4;
            for(int i=0;i<18;i++){angle=(lo+hi)/2;double[] r=fly(angle,far);flight=r[1];if(r[0]<up)lo=angle;else hi=angle;}
        }
        me.rotationYaw=(float)(Math.toDegrees(Math.atan2(dz,dx))-90);me.rotationPitch=(float)-Math.toDegrees(angle);
    }
    /** One step back is somewhere to stand: floor under it, room in it, no fluid. */
    private boolean clearBehind(){
        var me=mc.thePlayer;double yaw=Math.toRadians(me.rotationYaw);
        int x=(int)Math.floor(me.posX+Math.sin(yaw)*1.3),y=(int)Math.floor(me.boundingBox.minY+.01),z=(int)Math.floor(me.posZ-Math.cos(yaw)*1.3);
        var w=mc.theWorld;
        return w.getBlock(x,y-1,z).getMaterial().isSolid()&&!w.getBlock(x,y,z).getMaterial().blocksMovement()&&!w.getBlock(x,y,z).getMaterial().isLiquid()&&!w.getBlock(x,y+1,z).getMaterial().blocksMovement();
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
        if(shots>0||ranged||!adjustments.isEmpty()){out.put("shots",shots);out.put("hitsObserved",hits);out.put("weapon",weaponKey);
            out.put("ballistics",Map.of("speed",speed,"gravity",gravity,"drag",drag,"drawTicks",drawTicks,"reloadTicks",reloadTicks,"clickAfterLoad",clickAfterLoad));
            out.put("adjustments",adjustments);out.put("shotEntities",List.copyOf(shotKinds));
            out.put("tracks",tracks.stream().map(t->t.stream().map(s->List.of(s[0],s[1])).toList()).toList());} // per shot, per tick: [horizontal speed, vertical speed]
        if(me==player){
            out.put("health",me.getHealth());
            Map<String,Object> t=null;
            if(target!=null){t=new LinkedHashMap<>();t.put("entityId",target.getEntityId());t.put("type",String.valueOf(net.minecraft.entity.EntityList.getEntityString(target)));t.put("class",target.getClass().getName());
                t.put("health",target instanceof EntityLivingBase l?l.getHealth():null);t.put("hostile",hostile.test(target));t.put("distance",Math.round(target.getDistanceToEntity(me)*10)/10.0);}
            out.put("target",t);
            // Whoever is left is the next decision: the same rows obs.entities gives, so nothing here is new knowledge.
            out.put("hostilesInSight",hostiles(16).stream().map(e->Map.of("entityId",e.getEntityId(),"type",String.valueOf(net.minecraft.entity.EntityList.getEntityString(e)),"distance",Math.round(e.getDistanceToEntity(me)*10)/10.0)).toList());
        }
        out.put("hostileRule",hostileRule);out.put("jobSettings",jobSettings);
        out.put("controlOwned",!done()&&lease.isActive());out.put("scope",scope);return out;
    }
}
