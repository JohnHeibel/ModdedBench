// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
package dev.modbench.server;

import dev.modbench.bridge.Json;
import java.lang.reflect.*;
import java.util.*;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.*;
import net.minecraft.inventory.Slot;
import net.minecraft.item.*;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;

/** All state is contained by FluidFixture's player/air-volume recovery journal. */
final class UiFixture {
    private final WorldServer world;
    private final EntityPlayerMP player;
    private final int ox,oz;
    private static final List<String> NAMES=List.of("chest","crafting","furnace","anvil","tinkers","gregtech","ae2","forestry","ae_terminal");
    UiFixture(WorldServer world,EntityPlayerMP player,int x,int z) {this.world=world;this.player=player;ox=x;oz=z;}
    private void block(int x,int z,Block block,int meta) {world.setBlock(ox+x,176,oz+z,block,meta,3);}
    void create() throws Exception {
        for(int x=0;x<32;x++) for(int z=0;z<16;z++) world.setBlock(ox+x,175,oz+z,Blocks.glowstone,0,3);
        block(3,2,Blocks.chest,0);block(7,2,Blocks.crafting_table,0);block(11,2,Blocks.furnace,0);block(15,2,Blocks.anvil,0);
        block(19,2,registered("TConstruct:CraftingStation"),0);
        Object[] catalog=(Object[])Class.forName("gregtech.api.GregTechAPI").getField("METATILEENTITIES").get(null);
        Block machines=(Block)Class.forName("gregtech.api.GregTechAPI").getField("sBlockMachines").get(null);
        block(23,2,machines,((Number)call(catalog[301],"getTileEntityBaseType")).intValue());
        TileEntity machine=world.getTileEntity(ox+23,176,oz+2);
        call(machine,"setInitialValuesAsNBT",null,(short)301);call(machine,"setOwnerUuid",player.getUniqueID());
        block(27,2,registered("appliedenergistics2:tile.BlockCellWorkbench"),0);
        block(3,8,registered("Forestry:factory2"),2);
        for(int i=0;i<36;i++) player.inventory.setInventorySlotContents(i,null);
        player.inventory.setInventorySlotContents(9,new ItemStack(Items.paper,32));
        player.inventory.setInventorySlotContents(10,new ItemStack(Blocks.cobblestone,64));
        player.inventory.setInventorySlotContents(11,new ItemStack(Blocks.planks,32));
        player.inventory.setInventorySlotContents(12,new ItemStack(Blocks.wool,8,1));
        player.inventory.setInventorySlotContents(13,new ItemStack(Blocks.wool,8,2));
        player.inventory.setInventorySlotContents(14,new ItemStack(Items.coal,8));
        player.inventory.setInventorySlotContents(15,new ItemStack(Items.porkchop,8));
        player.inventory.setInventorySlotContents(16,new ItemStack(Items.paper,4).setStackDisplayName("Variant A"));
        player.inventory.setInventorySlotContents(17,new ItemStack(Items.paper,4).setStackDisplayName("Variant B"));
        Item cell=(Item)Item.itemRegistry.getObject("appliedenergistics2:item.ItemBasicStorageCell.1k");
        if(cell==null) throw new IllegalStateException("AE2 fixture storage cell unavailable");
        player.inventory.setInventorySlotContents(18,new ItemStack(cell,1));
        player.inventory.currentItem=0;player.experienceLevel=30;
        player.playerNetServerHandler.sendPacket(new net.minecraft.network.play.server.S09PacketHeldItemChange(0));
        player.playerNetServerHandler.sendPacket(new net.minecraft.network.play.server.S1FPacketSetExperience(player.experience,player.experienceTotal,player.experienceLevel));
        player.inventoryContainer.detectAndSendChanges();
        new AeTerminalFixture(world,player,ox,oz).create();
    }
    Object position(String name) {
        int index=NAMES.indexOf(name);if(index<0) throw new IllegalArgumentException("unknown GUI fixture");
        if(player.inventory.getItemStack()!=null) throw new IllegalArgumentException("return cursor before fixture teleport");
        player.closeScreen();int x=index>=7?3+(index-7)*4:3+index*4,z=index>=7?8:2;
        player.motionX=player.motionY=player.motionZ=0;player.fallDistance=0;
        player.playerNetServerHandler.setPlayerLocation(ox+x-1.5,176,oz+z+.5,-90,29);
        return Json.object("name",name,"block",Json.object("x",ox+x,"y",176,"z",oz+z),"cases",NAMES);
    }
    Object status() {
        var slots=new ArrayList<>();
        for(Object value:player.openContainer.inventorySlots) {Slot slot=(Slot)value;slots.add(Json.object("i",slot.slotNumber,"stack",stack(slot.getStack())));}
        return Json.object("windowId",player.openContainer.windowId,"class",player.openContainer.getClass().getName(),"slots",slots,"cursor",stack(player.inventory.getItemStack()),"ae",new AeTerminalFixture(world,player,ox,oz).status());
    }
    private static Object stack(ItemStack stack) {
        return stack==null?null:Json.object("id",Item.itemRegistry.getNameForObject(stack.getItem()),"meta",stack.getItemDamage(),"count",stack.stackSize,"name",stack.getDisplayName(),"nbt",stack.hasTagCompound()?stack.getTagCompound().toString():null);
    }
    private static Block registered(String id) {
        Object block=Block.blockRegistry.getObject(id);if(!(block instanceof Block value)||value==Blocks.air) throw new IllegalArgumentException("missing fixture block "+id);return value;
    }
    private static Object call(Object target,String name,Object...args) throws Exception {
        for(Method m:target.getClass().getMethods()) if(m.getName().equals(name)&&m.getParameterCount()==args.length) try {return m.invoke(target,args);}catch(IllegalArgumentException mismatch) {}
        throw new NoSuchMethodException(name);
    }
}
