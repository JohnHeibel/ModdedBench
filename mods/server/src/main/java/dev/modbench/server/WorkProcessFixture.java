// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
package dev.modbench.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.modbench.bridge.Json;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.block.Block;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.JsonToNBT;
import net.minecraft.nbt.*;
import net.minecraft.network.play.server.S09PacketHeldItemChange;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.WorldServer;

/**
 * Journalled, dev-only mining/building course layered inside FluidFixture's bounded empty-sky work volume.
 * FluidFixture owns the player snapshot, ToolBuilder loadout, selected-slot packet and health restoration.
 */
final class WorkProcessFixture {
    private static final int WIDTH=32, DEPTH=16, FLOOR=175, TOP=185;
    private final MinecraftServer server;
    private final FluidFixture work;
    private final File journal;
    private NBTTagCompound saved;
    private int x,z;

    WorkProcessFixture(MinecraftServer server) {
        this.server=server;this.work=new FluidFixture(server);
        this.journal=new File(world().getSaveHandler().getWorldDirectory(),"modbench-work-process-fixture.dat");
        if(journal.isFile()) try(FileInputStream in=new FileInputStream(journal)) {
            saved=CompressedStreamTools.readCompressed(in);x=saved.getInteger("x");z=saved.getInteger("z");
        } catch(Exception e) {throw new IllegalStateException("cannot read work-process fixture recovery journal",e);}
    }

    Object create() throws Exception {
        if(saved!=null) throw new IllegalArgumentException("restore existing work-process fixture first");
        JsonObject origin=(JsonObject)work.createWork();
        x=origin.getAsJsonArray("origin").get(0).getAsInt();z=origin.getAsJsonArray("origin").get(2).getAsInt();
        saved=new NBTTagCompound();saved.setInteger("x",x);saved.setInteger("z",z);saved.setString("uuid",player().getUniqueID().toString());
        write();
        try {
            clearArena();buildArena();buildTargets();buildSelectionArea();buildDescendingCase();
            return position("ore_line");
        } catch(Exception|LinkageError e) {try {restore();} catch(Exception cleanup) {e.addSuppressed(cleanup);} throw e;}
    }

    /** Fixture-only block setter; rejects every position outside the FluidFixture journalled volume. */
    Object setBlock(JsonObject params) {
        require();int relativeX=Json.integer(params,"x",0,0,WIDTH-1),y=Json.integer(params,"y",FLOOR,FLOOR,TOP),relativeZ=Json.integer(params,"z",0,0,DEPTH-1),meta=Json.integer(params,"meta",0,0,15);
        String id=Json.string(params,"id","");Block block=(Block)Block.blockRegistry.getObject(id);
        if(block==null || !id.equals(Block.blockRegistry.getNameForObject(block))) throw new IllegalArgumentException("exact registered block id required");
        if(meta<0||meta>15) throw new IllegalArgumentException("metadata must be 0..15");
        set(relativeX,y,relativeZ,block,meta);return status();
    }

    /** Fixture-only stack setter, scoped to the journalled fixture player and normal container synchronization. */
    Object setStack(JsonObject params) throws Exception {
        require();int slot=Json.integer(params,"slot",0,0,35),count=Json.integer(params,"count",1,1,64),meta=Json.integer(params,"meta",0,0,32767);
        String id=Json.string(params,"id","");
        Object raw=net.minecraft.item.Item.itemRegistry.getObject(id);
        if(!(raw instanceof net.minecraft.item.Item item) || !id.equals(net.minecraft.item.Item.itemRegistry.getNameForObject(item))) throw new IllegalArgumentException("exact registered item id required; use NEI discovery");
        ItemStack stack=new ItemStack(item,count,meta);
        if(params.has("nbt")) stack.setTagCompound((NBTTagCompound)JsonToNBT.func_150315_a(Json.string(params,"nbt","{}")));
        EntityPlayerMP p=player();p.inventory.setInventorySlotContents(slot,stack);
        // The player and client must agree on the selected hotbar entry before a native tool test begins.
        p.playerNetServerHandler.sendPacket(new S09PacketHeldItemChange(p.inventory.currentItem));
        p.inventoryContainer.detectAndSendChanges();p.sendContainerAndContentsToPlayer(p.inventoryContainer,p.inventoryContainer.getInventory());
        return status();
    }

    /** Read-only native machine evidence, confined to the existing recovery volume. */
    Object inspectBlock(JsonObject params) {
        require();int dx=Json.integer(params,"x",0,0,WIDTH-1),y=Json.integer(params,"y",176,FLOOR,TOP),dz=Json.integer(params,"z",0,0,DEPTH-1);
        int wx=x+dx,wz=z+dz;var block=world().getBlock(wx,y,wz);var tile=world().getTileEntity(wx,y,wz);
        JsonObject out=blockStatus("inspection",dx,y,dz);
        // Pick-block is a client callback: vanilla's fallback refers to a
        // client-only Item accessor stripped from a dedicated server. Use the
        // serialized tile identity here as independent authoritative evidence.
        if(tile!=null) {
            NBTTagCompound tag=new NBTTagCompound();tile.writeToNBT(tag);out.addProperty("tileClass",tile.getClass().getName());out.add("tile",Json.GSON.toJsonTree(nbt(tag)));
            if(tile instanceof net.minecraft.inventory.IInventory inventory){List<Object> slots=new ArrayList<>();for(int i=0;i<inventory.getSizeInventory();i++)slots.add(stack(inventory.getStackInSlot(i)));out.add("inventory",Json.GSON.toJsonTree(slots));}
            if(tile instanceof net.minecraftforge.fluids.IFluidHandler handler){List<Object> tanks=new ArrayList<>();for(var tank:handler.getTankInfo(net.minecraftforge.common.util.ForgeDirection.UNKNOWN))if(tank!=null)tanks.add(Json.object("capacity",tank.capacity,"fluid",tank.fluid==null?null:Json.object("id",tank.fluid.getFluid().getName(),"amount",tank.fluid.amount)));out.add("tanks",Json.GSON.toJsonTree(tanks));}
        }
        return out;
    }

    /** Energy is a finite fixture input; never changes faces, connections, modes or recipe inputs. */
    Object supplyEnergy(JsonObject params) throws Exception {
        require();int dx=Json.integer(params,"x",0,0,WIDTH-1),y=Json.integer(params,"y",176,FLOOR,TOP),dz=Json.integer(params,"z",0,0,DEPTH-1),eu=Json.integer(params,"eu",2048,0,32768);
        var tile=world().getTileEntity(x+dx,y,z+dz);if(tile==null)throw new IllegalArgumentException("fixture machine required");
        long capacity=((Number)tile.getClass().getMethod("getEUCapacity").invoke(tile)).longValue();
        tile.getClass().getMethod("setStoredEU",long.class).invoke(tile,Math.min(eu,capacity));tile.markDirty();return inspectBlock(params);
    }
    private static Object stack(ItemStack stack){return stack==null?null:Json.object("id",net.minecraft.item.Item.itemRegistry.getNameForObject(stack.getItem()),"meta",stack.getItemDamage(),"count",stack.stackSize,"nbt",stack.hasTagCompound()?stack.getTagCompound().toString():null);}
    private static Object nbt(NBTBase tag) {
        if(tag instanceof NBTTagCompound compound){java.util.Map<String,Object> out=new java.util.LinkedHashMap<>();for(Object key:compound.func_150296_c())out.put((String)key,nbt(compound.getTag((String)key)));return out;}
        if(tag instanceof NBTTagList list){List<Object> out=new ArrayList<>();NBTTagList copy=(NBTTagList)list.copy();while(copy.tagCount()>0)out.add(nbt(copy.removeTag(0)));return out;}
        if(tag instanceof NBTBase.NBTPrimitive number){if(tag.getId()<=4)return number.func_150291_c();return number.func_150286_g();}
        if(tag instanceof NBTTagString value)return value.func_150285_a_();
        if(tag instanceof NBTTagByteArray value)return value.func_150292_c();
        if(tag instanceof NBTTagIntArray value)return value.func_150302_c();
        return tag.toString();
    }

    Object position(String name) {
        require();int dx,dy,dz;
        switch(name) {
            case "ore_line" -> {dx=1;dy=176;dz=2;}
            case "selection" -> {dx=2;dy=176;dz=8;}
            case "build" -> {dx=8;dy=176;dz=11;}
            case "descend_start" -> {dx=20;dy=179;dz=3;}
            case "descend_step_1" -> {dx=21;dy=178;dz=3;}
            case "descend_step_2" -> {dx=22;dy=177;dz=3;}
            case "descend_goal" -> {dx=23;dy=176;dz=3;}
            default -> throw new IllegalArgumentException("position must be ore_line, selection, build, descend_start, descend_step_1, descend_step_2 or descend_goal");
        }
        EntityPlayerMP p=player();p.closeScreen();p.mountEntity(null);p.motionX=p.motionY=p.motionZ=0;p.fallDistance=0;
        p.playerNetServerHandler.setPlayerLocation(x+dx+.5,dy,z+dz+.5,0,0);
        return status();
    }

    Object status() {
        require();EntityPlayerMP p=player();JsonArray blocks=new JsonArray();
        for(Target target:targets()) blocks.add(blockStatus(target.name,target.dx,target.y,target.dz));
        JsonArray inventory=new JsonArray();
        for(int slot=0;slot<36;slot++) {ItemStack stack=p.inventory.getStackInSlot(slot);if(stack!=null) inventory.add(Json.object("slot",slot,"id",net.minecraft.item.Item.itemRegistry.getNameForObject(stack.getItem()),"meta",stack.getItemDamage(),"count",stack.stackSize,"nbt",stack.hasTagCompound()?stack.getTagCompound().toString():null));}
        return Json.object("active",true,"origin",Json.array(x,FLOOR,z),"bounds",Json.object("min",Json.array(x,FLOOR,z),"maxExclusive",Json.array(x+WIDTH,TOP+1,z+DEPTH)),
            "areas",Json.object("oreLine",Json.array(x+1,176,z+2,x+8,176,z+2),"selection",Json.array(x+2,176,z+8,x+14,179,z+13),"descending",Json.array(x+20,176,z+3,x+23,179,z+3)),
            "targets",blocks,"selectedSlot",p.inventory.currentItem,"inventory",inventory,"loadout","FluidFixture ToolBuilder Tinkers loadout");
    }

    Object restore() throws Exception {
        if(saved==null) return Json.object("restored",false);
        clearDrops();Object restored=work.restore();
        if(!journal.delete()) throw new IOException("cannot delete work-process fixture journal");
        saved=null;return restored;
    }

    private void clearArena() {for(int dx=0;dx<WIDTH;dx++)for(int dz=0;dz<DEPTH;dz++)for(int y=FLOOR;y<=TOP;y++)set(dx,y,dz,Blocks.air,0);}
    private void buildArena() {
        for(int dx=0;dx<WIDTH;dx++)for(int dz=0;dz<DEPTH;dz++) set(dx,FLOOR,dz,Blocks.glowstone,0);
        for(int dx=0;dx<WIDTH;dx++)for(int dz=0;dz<DEPTH;dz++) if(dx==0||dz==0||dx==WIDTH-1||dz==DEPTH-1) for(int y=176;y<=184;y++) set(dx,y,dz,Blocks.glass,0);
        for(int dx=0;dx<WIDTH;dx++)for(int dz=0;dz<DEPTH;dz++) set(dx,184,dz,Blocks.glass,0);
        for(int dx=2;dx<WIDTH-2;dx+=6) for(int dz=2;dz<DEPTH-2;dz+=5) set(dx,183,dz,Blocks.glowstone,0);
    }
    private void buildTargets() {
        set(2,176,2,Blocks.coal_ore,0);set(3,176,2,Blocks.iron_ore,0);set(4,176,2,Blocks.gold_ore,0);
        set(5,176,2,Blocks.lapis_ore,0);set(6,176,2,Blocks.redstone_ore,0);set(7,176,2,Blocks.lit_redstone_ore,0);
        // Same registered block with distinct metadata verifies exact ID+meta observation without special cases.
        set(8,176,2,Blocks.stone,1);set(9,176,2,Blocks.stone,3);
    }
    private void buildSelectionArea() {
        // Cobblestone in FluidFixture's existing work loadout funds the empty cells in this bounded partial build.
        for(int dx=2;dx<=14;dx++) for(int dz=8;dz<=13;dz++) if(dx==2||dx==14||dz==8||dz==13) set(dx,175,dz,Blocks.stone,0);
        set(3,176,9,Blocks.cobblestone,0);set(4,176,9,Blocks.wool,14);set(5,176,9,Blocks.wool,11);
        set(3,177,9,Blocks.cobblestone,0);set(5,177,9,Blocks.stone_slab,8);
        set(12,176,12,Blocks.cobblestone,0);set(13,176,12,Blocks.cobblestone,0);
    }
    private void buildDescendingCase() {
        // Three descending, harvestable faces: surface y=179 -> 178 -> 177 -> 176. Each landing is two-wide,
        // with a same-height dry cardinal escape; do not weaken MiningJob's escape-step safety check for a fixture.
        for(int dz=2;dz<=4;dz++) {
            set(19,178,dz,Blocks.stone,0);set(20,178,dz,Blocks.stone,0); // source platform extends backward
            set(21,177,dz,Blocks.stone,0);set(22,176,dz,Blocks.stone,0);set(23,175,dz,Blocks.stone,0);
        }
        set(21,178,3,Blocks.iron_ore,0);set(22,177,3,Blocks.gold_ore,0);set(23,176,3,Blocks.redstone_ore,0);
        for(int dx=20;dx<=23;dx++) {set(dx,180,2,Blocks.glass,0);set(dx,180,4,Blocks.glass,0);} // visible guard, no bypass platform
    }
    private List<Target> targets() {return List.of(new Target("coal",2,176,2),new Target("iron",3,176,2),new Target("gold",4,176,2),new Target("lapis",5,176,2),new Target("redstone",6,176,2),new Target("lit_redstone",7,176,2),new Target("granite_meta",8,176,2),new Target("diorite_meta",9,176,2),new Target("descend_1",21,178,3),new Target("descend_2",22,177,3),new Target("descend_3",23,176,3));}
    private JsonObject blockStatus(String name,int dx,int y,int dz) {Block block=world().getBlock(x+dx,y,z+dz);return Json.object("name",name,"pos",Json.array(x+dx,y,z+dz),"id",Block.blockRegistry.getNameForObject(block),"meta",world().getBlockMetadata(x+dx,y,z+dz));}
    private void set(int dx,int y,int dz,Block block,int meta) {if(dx<0||dx>=WIDTH||dz<0||dz>=DEPTH||y<FLOOR||y>TOP) throw new IllegalArgumentException("fixture block outside journalled bounds");world().setBlock(x+dx,y,z+dz,block,meta,3);}
    private void clearDrops() {AxisAlignedBB box=AxisAlignedBB.getBoundingBox(x,FLOOR,z,x+WIDTH,TOP+1,z+DEPTH);for(Object raw:world().getEntitiesWithinAABB(EntityItem.class,box))((EntityItem)raw).setDead();}
    private void require(){if(saved==null)throw new IllegalArgumentException("create work-process fixture first");if(!player().getUniqueID().toString().equals(saved.getString("uuid")))throw new IllegalArgumentException("fixture player mismatch");}
    private WorldServer world(){return server.worldServerForDimension(0);}
    private EntityPlayerMP player(){for(Object raw:server.getConfigurationManager().playerEntityList){EntityPlayerMP p=(EntityPlayerMP)raw;if(p.getCommandSenderName().equals("ModbenchDev")&&p.dimension==0)return p;}throw new IllegalArgumentException("fixture requires ModbenchDev in overworld");}
    private void write() throws Exception {try(FileOutputStream out=new FileOutputStream(journal)){CompressedStreamTools.writeCompressed(saved,out);}}
    private record Target(String name,int dx,int y,int dz) {}
}
