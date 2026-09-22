// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh;

import baritone.compat.Registry;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;

/** What happened to the player's body during a job, measured every tick with no idea what any block does: damage and
 *  its type, effects gained and lost, air lost, burning, webbed or slowed movement, each with the blocks at the feet, at
 *  the head and underfoot. The first occurrence of each is kept, with a count: evidence for the model's hazards list. */
final class Symptoms {
    private static final Queue<Object[]> hurts=new ConcurrentLinkedQueue<>();
    private static final java.lang.reflect.Field WEB=cpw.mods.fml.relauncher.ReflectionHelper.findField(net.minecraft.entity.Entity.class,"isInWeb","field_70134_J");
    private final Map<String,Map<String,Object>> seen=new LinkedHashMap<>();
    private int ticks,dropped,walking;
    private float health=-1;private int air;
    private Map<Integer,PotionEffect> effects=Map.of();
    private double lastX,lastZ;

    /** The damage type a dedicated server reported for the player's own hurt (ServerClock sends it). */
    static void hurt(String type,String by,float amount){if(hurts.size()<=64)hurts.add(new Object[]{type,by.isEmpty()?null:by,amount});}
    /** The damage type the integrated server gave the player's own hurt; the client alone only ever sees "generic". */
    static void hurt(net.minecraftforge.event.entity.living.LivingHurtEvent event){
        var me=Minecraft.getMinecraft().thePlayer;
        if(event.entityLiving.worldObj.isRemote||me==null||!event.entityLiving.getUniqueID().equals(me.getUniqueID())||hurts.size()>64)return;
        hurts.add(new Object[]{event.source.getDamageType(),event.source.getEntity()==null?null:net.minecraft.entity.EntityList.getEntityString(event.source.getEntity()),event.ammount});
    }
    void sample(EntityPlayerSP p){
        ticks++;
        Map<Integer,PotionEffect> now=new HashMap<>();
        for(Object o:p.getActivePotionEffects())if(o instanceof PotionEffect e)now.put(e.getPotionID(),e);
        if(health<0){health=p.getHealth();air=p.getAir();effects=now;lastX=p.posX;lastZ=p.posZ;hurts.clear();return;}
        boolean typed=false;
        for(Object[] h;(h=hurts.poll())!=null;typed=true)note(p,"damage",h[0]+(h[1]==null?"":" by "+h[1]),Map.of("amount",h[2]));
        if(!typed&&p.getHealth()<health)note(p,"damage","unknown",Map.of("amount",health-p.getHealth()));
        for(var e:now.values())if(!effects.containsKey(e.getPotionID()))note(p,"effect_gained",effect(e),Map.of("amplifier",e.getAmplifier(),"durationTicks",e.getDuration()));
        for(var e:effects.values())if(!now.containsKey(e.getPotionID()))note(p,"effect_lost",effect(e),Map.of());
        if(p.getAir()<air)note(p,"air_lost","",Map.of("air",p.getAir()));
        if(p.isBurning())note(p,"burning","",Map.of());
        try{if(WEB.getBoolean(p))note(p,"webbed","",Map.of());}catch(IllegalAccessException ignored){}
        // Walking on the ground, no wall, no water, no ladder, not sneaking: a player covers about 2.16 blocks per tick per
        // point of movement speed attribute. Well under half of that for half a second is a block slowing the player.
        double speed=Math.hypot(p.posX-lastX,p.posZ-lastZ),expected=p.getAIMoveSpeed()*2.16;
        walking=p.movementInput!=null&&p.movementInput.moveForward>.5f&&p.onGround&&!p.isCollidedHorizontally&&!p.isInWater()&&!p.isOnLadder()&&!p.isSneaking()?walking+1:0;
        if(walking>=10&&expected>0&&speed<expected*.5)note(p,"slowed","",Map.of("speedFraction",Math.round(speed/expected*100)/100.0));
        health=p.getHealth();air=p.getAir();effects=now;lastX=p.posX;lastZ=p.posZ;
    }
    private static String effect(PotionEffect e){Potion potion=e.getPotionID()<Potion.potionTypes.length?Potion.potionTypes[e.getPotionID()]:null;return potion==null?"potion "+e.getPotionID():potion.getName();}
    private void note(EntityPlayerSP p,String kind,String what,Map<String,Object> detail){
        // The local 1.7.10 player's posY is its eyes; its bounding box starts at its feet.
        int x=(int)Math.floor(p.posX),y=(int)Math.floor(p.boundingBox.minY+.001),z=(int)Math.floor(p.posZ);
        String feet=block(p,x,y,z),head=block(p,x,(int)Math.floor(p.posY),z),under=block(p,x,(int)Math.floor(p.boundingBox.minY-.01),z);
        String key=kind+"|"+what+"|"+feet+"|"+under+"|"+(kind.equals("air_lost")?head:"");
        var known=seen.get(key);
        if(known!=null){known.merge("count",1,(a,b)->(Integer)a+1);return;}
        if(seen.size()>=16){dropped++;return;}
        Map<String,Object> out=new LinkedHashMap<>();out.put("kind",kind);if(!what.isEmpty())out.put("what",what);out.putAll(detail);
        out.put("pos",List.of(x,y,z));out.put("feet",feet);out.put("head",head);out.put("under",under);out.put("tick",ticks);out.put("count",1);
        seen.put(key,out);
    }
    private static String block(EntityPlayerSP p,int x,int y,int z){
        if(!ForgeSnapshot.loaded(p.worldObj,x,y,z))return null;
        return Registry.name(p.worldObj.getBlock(x,y,z))+":"+p.worldObj.getBlockMetadata(x,y,z);
    }
    /** First occurrences (at most 16, each with its count) and how many distinct ones did not fit. */
    Map<String,Object> summary(){return Map.of("events",List.copyOf(seen.values()),"omitted",dropped,"ticksSampled",ticks);}
}
