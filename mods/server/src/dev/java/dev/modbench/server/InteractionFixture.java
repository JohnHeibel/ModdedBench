// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.server;

import com.google.gson.*;
import dev.modbench.bridge.Json;
import java.io.*;
import java.util.*;
import net.minecraft.entity.*;
import net.minecraft.entity.monster.EntityZombie;
import net.minecraft.entity.passive.EntityCow;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.*;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

/** Journalled dev-only native interaction arena; original player is restored by FluidFixture. */
final class InteractionFixture {
    private final MinecraftServer server;
    private final FluidFixture work;
    private final File journal;
    private NBTTagCompound saved;
    private int x,z;
    InteractionFixture(MinecraftServer server) {
        this.server=server;work=new FluidFixture(server);
        journal=new File(world().getSaveHandler().getWorldDirectory(),"modbench-interaction-fixture.dat");
        if(journal.isFile())try(var in=new FileInputStream(journal)){saved=CompressedStreamTools.readCompressed(in);x=saved.getInteger("x");z=saved.getInteger("z");}catch(Exception e){throw new IllegalStateException(e);}
    }
    WorldServer world(){return server.worldServerForDimension(0);}
    EntityPlayerMP player(){for(Object o:server.getConfigurationManager().playerEntityList){var p=(EntityPlayerMP)o;if(p.getCommandSenderName().equals("ModbenchDev")&&p.dimension==0)return p;}throw new IllegalArgumentException("ModbenchDev required");}
    Object create() throws Exception {
        if(saved!=null)throw new IllegalArgumentException("restore existing interaction fixture first");
        JsonObject start=(JsonObject)work.createWork();x=start.getAsJsonArray("origin").get(0).getAsInt();z=start.getAsJsonArray("origin").get(2).getAsInt();
        saved=new NBTTagCompound();saved.setInteger("x",x);saved.setInteger("z",z);saved.setTag("entities",new NBTTagList());write();
        try {
            // Clear the work obstacles; all cells are within its saved empty-sky volume.
            for(int dx=0;dx<32;dx++)for(int dz=0;dz<16;dz++){
                world().setBlock(x+dx,175,z+dz,Blocks.glowstone,0,3);
                for(int y=176;y<=184;y++)world().setBlock(x+dx,y,z+dz,dx==0||dx==31||dz==0||dz==15||y==184?Blocks.glass:Blocks.air,0,3);
            }
            world().setBlock(x+5,176,z+4,Blocks.chest,0,3);
            world().setBlock(x+5,175,z+7,Blocks.water,0,3);
            // Sealed basin floor underneath source remains within fixture volume (source raised one cell).
            world().setBlock(x+5,175,z+7,Blocks.stone,0,3);world().setBlock(x+5,176,z+7,Blocks.water,0,3);
            for(int dx=4;dx<=6;dx++)for(int dz=6;dz<=8;dz++)if(dx!=5||dz!=7)world().setBlock(x+dx,176,z+dz,Blocks.stone,0,3);
            var p=player();Arrays.fill(p.inventory.mainInventory,null);Arrays.fill(p.inventory.armorInventory,null);
            p.inventory.mainInventory[0]=new ItemStack(Items.bucket);p.inventory.mainInventory[1]=new ItemStack(Items.bread,4);
            p.inventory.mainInventory[2]=new ItemStack(Items.diamond_sword);p.inventory.mainInventory[3]=new ItemStack(Blocks.cobblestone,8);
            p.inventory.mainInventory[4]=new ItemStack(Items.milk_bucket);p.inventory.currentItem=8;
            p.playerNetServerHandler.sendPacket(new net.minecraft.network.play.server.S09PacketHeldItemChange(p.inventory.currentItem));
            NBTTagCompound food=new NBTTagCompound();p.getFoodStats().writeNBT(food);food.setInteger("foodLevel",8);p.getFoodStats().readNBT(food);
            // Vanilla canEat rejects invulnerable players. Keep real survival
            // capabilities so food tests cannot pass on client-only prediction.
            p.setHealth(20);p.capabilities.disableDamage=false;p.sendPlayerAbilities();
            p.playerNetServerHandler.sendPacket(new net.minecraft.network.play.server.S06PacketUpdateHealth(p.getHealth(),p.getFoodStats().getFoodLevel(),p.getFoodStats().getSaturationLevel()));
            p.inventoryContainer.detectAndSendChanges();p.sendContainerAndContentsToPlayer(p.inventoryContainer,p.inventoryContainer.getInventory());
            return position("chest");
        }catch(Exception|LinkageError e){try{restore();}catch(Exception cleanup){e.addSuppressed(cleanup);}throw e;}
    }
    Object position(String target) throws Exception {
        require();removeEntities();var p=player();p.closeScreen();p.motionX=p.motionY=p.motionZ=0;
        double px=x+3.5,pz=z+4.5;
        if(target.equals("fluid")||target.equals("lava")){
            px=x+5.5;pz=z+5.5;
            if(target.equals("lava"))world().setBlock(x+5,176,z+7,Blocks.lava,0,3);
        }
        else if(target.equals("entity")||target.equals("combat")||target.equals("occluded")) {
            px=x+12.5;pz=z+4.5;
            // Some pack damage hooks bypass capabilities. Give the journalled
            // test player a bounded health reserve for isolated offensive tests.
            p.getEntityAttribute(SharedMonsterAttributes.maxHealth).setBaseValue(200);p.setHealth(200);
            p.playerNetServerHandler.sendPacket(new net.minecraft.network.play.server.S06PacketUpdateHealth(p.getHealth(),p.getFoodStats().getFoodLevel(),p.getFoodStats().getSaturationLevel()));
            if(target.equals("entity"))spawn(new EntityCow(world()),x+14,176,z+4.5);
            else {
                EntityZombie zombie=new EntityZombie(world());zombie.setCurrentItemOrArmor(4,new ItemStack(Items.diamond_helmet));
                spawn(zombie,x+14,176,z+4.5);spawn(new EntityCow(world()),x+12.5,176,z+6);
            }
            for(int y=176;y<=179;y++)world().setBlock(x+13,y,z+4,target.equals("occluded")?Blocks.stone:Blocks.air,0,3);
        } else if(!target.equals("chest"))throw new IllegalArgumentException("target must be chest, fluid, entity, combat or occluded");
        p.playerNetServerHandler.setPlayerLocation(px,176,pz,-90,20);return status();
    }
    void spawn(EntityLiving e,double px,double py,double pz) throws Exception {
        e.setPosition(px,py,pz);e.func_110163_bv();
        NBTTagCompound record=new NBTTagCompound();record.setString("uuid",e.getUniqueID().toString());saved.getTagList("entities",10).appendTag(record);write();
        if(!world().spawnEntityInWorld(e))throw new IllegalStateException("fixture entity failed to spawn");
    }
    Object status(){require();JsonArray entities=new JsonArray();for(Object o:world().loadedEntityList){var e=(Entity)o;if(owned(e))entities.add(Json.object("entityId",e.getEntityId(),"uuid",e.getUniqueID().toString(),"type",EntityList.getEntityString(e),"health",e instanceof EntityLivingBase l?l.getHealth():null));}
        var p=player();var eye=net.minecraft.util.Vec3.createVectorHelper(p.posX,p.posY+p.getEyeHeight(),p.posZ);var look=p.getLook(1);
        JsonArray eyePosition=Json.array(eye.xCoord,eye.yCoord,eye.zCoord);
        var ray=world().rayTraceBlocks(eye,eye.addVector(look.xCoord*5,look.yCoord*5,look.zCoord*5),true);
        NBTTagCompound held=new NBTTagCompound();if(p.getHeldItem()!=null)p.getHeldItem().writeToNBT(held);
        return Json.object("origin",Json.array(x,175,z),"chest",Json.array(x+5,176,z+4),"fluid",Json.array(x+5,176,z+7),"entities",entities,"health",p.getHealth(),"food",p.getFoodStats().getFoodLevel(),"canEat",p.canEat(false),"invulnerable",p.capabilities.disableDamage,"usingItem",p.isUsingItem(),
            "pose",Json.object("pos",Json.array(p.posX,p.posY,p.posZ),"eye",eyePosition,"yaw",p.rotationYaw,"pitch",p.rotationPitch,"sneaking",p.isSneaking()),"selectedSlot",p.inventory.currentItem,"heldNbt",held.toString(),
            "fluidRay",ray==null?null:Json.object("pos",Json.array(ray.blockX,ray.blockY,ray.blockZ),"side",ray.sideHit,"canMine",world().canMineBlock(p,ray.blockX,ray.blockY,ray.blockZ),"spawnProtected",server.isBlockProtected(world(),ray.blockX,ray.blockY,ray.blockZ,p)));
    }
    Object hurt(int amount){require();var p=player();p.setHealth(Math.max(1,p.getHealth()-amount));return status();}
    Object stack(JsonObject params) throws Exception {
        require();var p=player();int slot=Json.integer(params,"slot",0,0,35);
        Object item=net.minecraft.item.Item.itemRegistry.getObject(Json.string(params,"id",""));
        if(!(item instanceof net.minecraft.item.Item value))throw new IllegalArgumentException("registered item required; use NEI discovery");
        ItemStack stack=new ItemStack(value,Json.integer(params,"count",1,1,64),Json.integer(params,"meta",0,0,32767));
        if(params.has("nbt"))stack.setTagCompound((NBTTagCompound)JsonToNBT.func_150315_a(Json.string(params,"nbt","{}")));
        p.inventory.setInventorySlotContents(slot,stack);p.inventoryContainer.detectAndSendChanges();p.sendContainerAndContentsToPlayer(p.inventoryContainer,p.inventoryContainer.getInventory());return status();
    }
    Object restore() throws Exception {if(saved==null)return Json.object("restored",false);removeEntities();Object out=work.restore();if(!journal.delete())throw new IOException("cannot delete interaction journal");saved=null;return out;}
    boolean owned(Entity e){if(saved==null)return false;var list=saved.getTagList("entities",10);for(int i=0;i<list.tagCount();i++)if(e.getUniqueID().toString().equals(list.getCompoundTagAt(i).getString("uuid")))return true;return false;}
    void removeEntities(){if(saved==null)return;for(int dx=0;dx<32;dx+=8)for(int dz=0;dz<16;dz+=8)world().getChunkFromChunkCoords((x+dx)>>4,(z+dz)>>4);for(Object o:new ArrayList<>(world().loadedEntityList)){var e=(Entity)o;if(owned(e))world().removeEntity(e);}}
    void require(){if(saved==null)throw new IllegalArgumentException("create interaction fixture first");}
    void write() throws Exception {try(var out=new FileOutputStream(journal)){CompressedStreamTools.writeCompressed(saved,out);}}
}
