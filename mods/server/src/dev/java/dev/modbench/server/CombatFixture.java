// SPDX-License-Identifier: MIT
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.modbench.bridge.Json;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.play.server.S09PacketHeldItemChange;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntityMobSpawner;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.GameRules;
import net.minecraft.world.WorldServer;

/**
 * A combat course's scene, in any pack: a closed box in the sky, ground built inside it (fill), mobs summoned into it by
 * registry name, the player's health, armour and inventory set for a trial, and all of it read back as the server sees
 * it. The box is dark on purpose (a spider in the light does not attack), so a course switches natural spawning off.
 * Nothing is journalled: it is for a throwaway test world, and arena {remove:true} takes the box away again.
 */
final class CombatFixture {
    private static final int FLOOR=176;
    private final MinecraftServer server;
    CombatFixture(MinecraftServer server){this.server=server;}
    private WorldServer world(){return server.worldServerForDimension(0);}
    private EntityPlayerMP player(){
        EntityPlayerMP found=null;
        for(Object o:server.getConfigurationManager().playerEntityList){
            EntityPlayerMP p=(EntityPlayerMP)o;
            if(p.playerNetServerHandler==null||!p.playerNetServerHandler.netManager.isChannelOpen())continue;
            if(found!=null)throw new IllegalArgumentException("a combat course needs exactly one connected player");
            found=p;
        }
        if(found==null)throw new IllegalArgumentException("a combat course needs a connected player");
        return found;
    }
    /** {x,z (low inside corner), width, depth (16..64), height (4..48), remove?}: floor top at y 176, air inside, stone all round and above. */
    Object arena(JsonObject p){
        WorldServer w=world();
        int x=Json.integer(p,"x",1024,-29999000,29999000),z=Json.integer(p,"z",1024,-29999000,29999000);
        int wide=Json.integer(p,"width",48,16,64),deep=Json.integer(p,"depth",48,16,64),high=Json.integer(p,"height",16,4,48);
        boolean remove=Json.bool(p,"remove",false);int changed=0;
        for(int cx=(x-1)>>4;cx<=(x+wide)>>4;cx++)for(int cz=(z-1)>>4;cz<=(z+deep)>>4;cz++)w.theChunkProviderServer.loadChunk(cx,cz);
        for(int dx=-1;dx<=wide;dx++)for(int dz=-1;dz<=deep;dz++)for(int y=FLOOR-1;y<=FLOOR+high;y++){
            boolean shell=dx<0||dz<0||dx==wide||dz==deep||y<FLOOR||y==FLOOR+high;
            Block b=remove||!shell?Blocks.air:Blocks.nether_brick; // nothing burns it, and no silverfish hides in it
            if(w.getBlock(x+dx,y,z+dz)==b)continue;
            w.removeTileEntity(x+dx,y,z+dz);w.setBlock(x+dx,y,z+dz,b,0,2);changed++;
        }
        return Json.object("origin",Json.array(x,FLOOR,z),"width",wide,"depth",deep,"height",high,"changed",changed,"removed",remove);
    }
    /**
     * {boxes:[{from:[x,y,z],to:[x,y,z],id (registry name),meta?,spawns? (an entity's registry name, for a block that is a
     * mob spawner)}]}: each box, corners included, filled with one block, in order,
     * 32768 cells at most a call. Set as the arena is, with no block told of its neighbour's change: a liquid stays the
     * still source it was placed as until something beside it changes. Rebuilding the arena clears it all.
     */
    Object fill(JsonObject p){
        WorldServer w=world();JsonArray boxes=p.getAsJsonArray("boxes");Block[] blocks=new Block[boxes.size()];int[][] at=new int[boxes.size()][];String[] spawns=new String[boxes.size()];long cells=0;
        for(int i=0;i<boxes.size();i++){ // all of it checked before any of it is placed
            JsonObject b=boxes.get(i).getAsJsonObject();String id=Json.string(b,"id","");
            if(!(Block.blockRegistry.getObject(id) instanceof Block block)||!id.equals(Block.blockRegistry.getNameForObject(block)))throw new IllegalArgumentException("exact registered block id required: "+id);
            JsonArray f=b.getAsJsonArray("from"),t=b.getAsJsonArray("to");int[] c=new int[7];
            for(int k=0;k<3;k++){c[k]=Math.min(f.get(k).getAsInt(),t.get(k).getAsInt());c[k+3]=Math.max(f.get(k).getAsInt(),t.get(k).getAsInt());}
            if(c[1]<1||c[4]>255)throw new IllegalArgumentException("a box must lie within y 1..255");
            c[6]=Json.integer(b,"meta",0,0,15);blocks[i]=block;at[i]=c;
            if(b.has("spawns")){spawns[i]=Json.string(b,"spawns","");
                if(!EntityList.stringToClassMapping.containsKey(spawns[i]))throw new IllegalArgumentException("no entity "+spawns[i]);
                if(!(block.hasTileEntity(c[6])&&block.createTileEntity(w,c[6]) instanceof TileEntityMobSpawner))throw new IllegalArgumentException(id+" is not a mob spawner");}
            cells+=(long)(c[3]-c[0]+1)*(c[4]-c[1]+1)*(c[5]-c[2]+1);
        }
        if(cells>32768)throw new IllegalArgumentException(cells+" cells: 32768 at most a call");
        int changed=0;
        for(int i=0;i<blocks.length;i++){int[] c=at[i];
            for(int cx=c[0]>>4;cx<=c[3]>>4;cx++)for(int cz=c[2]>>4;cz<=c[5]>>4;cz++)w.theChunkProviderServer.loadChunk(cx,cz);
            for(int x=c[0];x<=c[3];x++)for(int y=c[1];y<=c[4];y++)for(int z=c[2];z<=c[5];z++){
                if(w.getBlock(x,y,z)!=blocks[i]||w.getBlockMetadata(x,y,z)!=c[6]){w.removeTileEntity(x,y,z);w.setBlock(x,y,z,blocks[i],c[6],2);changed++;}
                if(spawns[i]!=null&&w.getTileEntity(x,y,z) instanceof TileEntityMobSpawner s){s.func_145881_a().setEntityName(spawns[i]);w.markBlockForUpdate(x,y,z);}
            }
        }
        return Json.object("cells",cells,"changed",changed);
    }
    /** {type (registry name), x,y,z, count?:1..16, spread?, health?}: spawned as a spawn egg would, and kept from despawning. */
    Object summon(JsonObject p){
        WorldServer w=world();String type=Json.string(p,"type","");
        int count=Json.integer(p,"count",1,1,16);double spread=Json.number(p,"spread",1.5,0,8);
        double x=Json.number(p,"x",0,-3e7,3e7),y=Json.number(p,"y",FLOOR,1,255),z=Json.number(p,"z",0,-3e7,3e7);
        JsonArray out=new JsonArray();
        for(int i=0;i<count;i++){
            Entity e=EntityList.createEntityByName(type,w);
            if(e==null)throw new IllegalArgumentException("no entity is registered as "+type);
            e.setLocationAndAngles(x,y,z+(i-(count-1)/2.0)*spread,0,0);
            if(e instanceof EntityLiving living){
                living.onSpawnWithEgg(null);living.func_110163_bv();
                if(p.has("health"))living.setHealth((float)Json.number(p,"health",living.getMaxHealth(),1,living.getMaxHealth()));
            }
            if(!w.spawnEntityInWorld(e))throw new IllegalStateException(type+" did not spawn (unloaded chunk, or a hostile mob on Peaceful)");
            out.add(describe(e));
        }
        return Json.object("summoned",out);
    }
    /** {radius?}: the player, and every other entity within radius of them (mobs, projectiles, drops). */
    Object status(JsonObject p){
        EntityPlayerMP pl=player();double r=Json.number(p,"radius",80,1,160);JsonArray list=new JsonArray();
        for(Object o:pl.worldObj.loadedEntityList){Entity e=(Entity)o;if(e!=pl&&!e.isDead&&e.getDistanceSqToEntity(pl)<=r*r){JsonObject d=describe(e);if(e instanceof EntityLivingBase)d.addProperty("stare",stare(pl,e));list.add(d);}}
        return Json.object("player",self(pl),"entities",list,"difficulty",pl.worldObj.difficultySetting.getDifficultyId(),"serverTick",server.getTickCounter());
    }
    /** {radius?}: removes every entity but players within radius of the player. */
    Object clear(JsonObject p){
        EntityPlayerMP pl=player();double r=Json.number(p,"radius",80,1,160);int removed=0;
        for(Object o:new ArrayList<>(pl.worldObj.loadedEntityList)){Entity e=(Entity)o;
            if(e instanceof EntityPlayer||e.getDistanceSqToEntity(pl)>r*r)continue;
            pl.worldObj.removeEntity(e);removed++;}
        return Json.object("removed",removed);
    }
    /**
     * The living player made ready for a trial: effects and fire gone, {health?,food?}, {empty?} inventory,
     * {armor:[{slot:0..3 (boots first),id,meta?}]}, {stacks:[{slot:0..35,id,meta?,count?}]}, {at:[x,y,z],yaw?,pitch?} in the overworld,
     * and {effects:[{id (number, or name as the game has it: nightVision or potion.nightVision),seconds?,amplifier?}]} given afresh.
     */
    Object reset(JsonObject p){
        EntityPlayerMP pl=player();
        if(pl.isDead||pl.getHealth()<=0)throw new IllegalArgumentException("the player is dead: respawn from the client first");
        pl.clearActivePotions();pl.extinguish();pl.fallDistance=0;pl.hurtResistantTime=0;pl.motionX=pl.motionY=pl.motionZ=0;
        pl.setHealth((float)Json.number(p,"health",pl.getMaxHealth(),1,pl.getMaxHealth()));
        NBTTagCompound food=new NBTTagCompound();pl.getFoodStats().writeNBT(food);
        food.setInteger("foodLevel",Json.integer(p,"food",20,0,20));food.setFloat("foodSaturationLevel",5);food.setFloat("foodExhaustionLevel",0);pl.getFoodStats().readNBT(food);
        if(Json.bool(p,"empty",false)){Arrays.fill(pl.inventory.mainInventory,null);Arrays.fill(pl.inventory.armorInventory,null);}
        if(p.has("armor"))for(JsonElement e:p.getAsJsonArray("armor")){JsonObject a=e.getAsJsonObject();pl.inventory.armorInventory[Json.integer(a,"slot",0,0,3)]=stack(a);}
        if(p.has("stacks"))for(JsonElement e:p.getAsJsonArray("stacks")){JsonObject a=e.getAsJsonObject();pl.inventory.mainInventory[Json.integer(a,"slot",0,0,35)]=stack(a);}
        if(p.has("at")){
            if(pl.dimension!=0)throw new IllegalArgumentException("the player must be in the overworld to be placed");
            JsonArray at=p.getAsJsonArray("at");double x=at.get(0).getAsDouble(),y=at.get(1).getAsDouble(),z=at.get(2).getAsDouble();
            for(int cx=((int)Math.floor(x)>>4)-2;cx<=((int)Math.floor(x)>>4)+2;cx++)for(int cz=((int)Math.floor(z)>>4)-2;cz<=((int)Math.floor(z)>>4)+2;cz++)world().theChunkProviderServer.loadChunk(cx,cz);
            pl.playerNetServerHandler.setPlayerLocation(x,y,z,(float)Json.number(p,"yaw",0,-360,360),(float)Json.number(p,"pitch",0,-90,90));
        }
        if(p.has("effects"))for(JsonElement e:p.getAsJsonArray("effects")){JsonObject a=e.getAsJsonObject();
            pl.addPotionEffect(new PotionEffect(potion(a.get("id")),Json.integer(a,"seconds",600,1,1000000)*20,Json.integer(a,"amplifier",0,0,255)));}
        pl.playerNetServerHandler.sendPacket(new S09PacketHeldItemChange(pl.inventory.currentItem));
        pl.inventoryContainer.detectAndSendChanges();pl.sendContainerAndContentsToPlayer(pl.inventoryContainer,pl.inventoryContainer.getInventory());
        return self(pl);
    }
    private static int potion(JsonElement id){
        if(id!=null&&id.getAsJsonPrimitive().isNumber()){int n=id.getAsInt();if(n>=0&&n<Potion.potionTypes.length&&Potion.potionTypes[n]!=null)return n;}
        else if(id!=null)for(Potion t:Potion.potionTypes)if(t!=null&&(t.getName().equals(id.getAsString())||t.getName().equals("potion."+id.getAsString())))return t.id;
        throw new IllegalArgumentException("no potion effect "+id);
    }
    /** {amount:1..19}: plain damage to the player, armour ignored, for a guard to see. */
    Object hurt(JsonObject p){EntityPlayerMP pl=player();pl.hurtResistantTime=0;pl.attackEntityFrom(net.minecraft.util.DamageSource.outOfWorld,(float)Json.number(p,"amount",1,1,19));return self(pl);}
    /** {rules?:{name:bool}, difficulty?:1..3}: sets game rules that exist and the difficulty; returns both as they now are. */
    Object rules(JsonObject p){
        GameRules g=world().getGameRules();
        if(p.has("rules"))for(Map.Entry<String,JsonElement> e:p.getAsJsonObject("rules").entrySet()){
            if(!g.hasRule(e.getKey()))throw new IllegalArgumentException("no game rule "+e.getKey());
            g.setOrCreateGameRule(e.getKey(),String.valueOf(e.getValue().getAsBoolean()));
        }
        if(p.has("difficulty"))server.func_147139_a(EnumDifficulty.getDifficultyEnum(Json.integer(p,"difficulty",1,1,3)));
        JsonObject rules=new JsonObject();for(String name:g.getRules())rules.addProperty(name,g.getGameRuleStringValue(name));
        return Json.object("rules",rules,"difficulty",world().difficultySetting.getDifficultyId());
    }
    private static ItemStack stack(JsonObject a){
        String id=Json.string(a,"id","");
        if(!(Item.itemRegistry.getObject(id) instanceof Item item)||!id.equals(Item.itemRegistry.getNameForObject(item)))throw new IllegalArgumentException("exact registered item id required: "+id);
        return new ItemStack(item,Json.integer(a,"count",1,1,64),Json.integer(a,"meta",0,0,32767));
    }
    private static JsonObject describe(Entity e){
        EntityLivingBase living=e instanceof EntityLivingBase l?l:null;
        return Json.object("entityId",e.getEntityId(),"uuid",e.getUniqueID().toString(),"type",EntityList.getEntityString(e),"class",e.getClass().getName(),
            "pos",Json.array(r(e.posX),r(e.posY),r(e.posZ)),"health",living==null?null:living.getHealth(),"maxHealth",living==null?null:living.getMaxHealth(),"burning",e.isBurning());
    }
    /** The player's look meets the middle of its body within the narrow cone a mob that minds being looked at tests, for the traces. */
    private static boolean stare(EntityPlayerMP pl,Entity e){
        net.minecraft.util.Vec3 look=pl.getLook(1.0F).normalize();
        net.minecraft.util.Vec3 to=net.minecraft.util.Vec3.createVectorHelper(e.posX-pl.posX,e.boundingBox.minY+e.height/2-(pl.posY+pl.getEyeHeight()),e.posZ-pl.posZ);
        double d=to.lengthVector();return d>0&&look.dotProduct(to.normalize())>1-0.025/d&&pl.canEntityBeSeen(e);
    }
    private static JsonObject self(EntityPlayerMP pl){
        JsonArray inventory=new JsonArray(),armor=new JsonArray();
        for(int i=0;i<pl.inventory.mainInventory.length;i++){ItemStack s=pl.inventory.mainInventory[i];if(s!=null)inventory.add(item(i,s));}
        for(int i=0;i<pl.inventory.armorInventory.length;i++){ItemStack s=pl.inventory.armorInventory[i];if(s!=null)armor.add(item(i,s));}
        return Json.object("name",pl.getCommandSenderName(),"dimension",pl.dimension,"pos",Json.array(r(pl.posX),r(pl.posY),r(pl.posZ)),"health",pl.getHealth(),
            "food",pl.getFoodStats().getFoodLevel(),"dead",pl.isDead||pl.getHealth()<=0,"burning",pl.isBurning(),"held",pl.inventory.currentItem,"inventory",inventory,"armor",armor,
            "look",Json.array(r(pl.rotationYaw),r(pl.rotationPitch)),"eyeY",r(pl.posY+pl.getEyeHeight()));
    }
    private static JsonObject item(int slot,ItemStack s){return Json.object("slot",slot,"id",Item.itemRegistry.getNameForObject(s.getItem()),"meta",s.getItemDamage(),"count",s.stackSize);}
    private static double r(double v){return Math.round(v*1000)/1000.0;}
}
