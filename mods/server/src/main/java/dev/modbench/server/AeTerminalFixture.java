// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
package dev.modbench.server;

import dev.modbench.bridge.Json;
import java.lang.reflect.*;
import java.util.*;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.*;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.*;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.ForgeDirection;

/** Native powered ME network, contained by the GUI fixture journal. */
final class AeTerminalFixture {
    private final WorldServer world;
    private final EntityPlayerMP player;
    private final int ox,oz;
    AeTerminalFixture(WorldServer world,EntityPlayerMP player,int x,int z) {this.world=world;this.player=player;ox=x;oz=z;}
    private void block(int x,int z,String id) {world.setBlock(ox+x,176,oz+z,(Block)Block.blockRegistry.getObject(id),0,3);}
    private Object drive() {return world.getTileEntity(ox+9,176,oz+8);}
    private Object registry() throws Exception {return call(call(Class.forName("appeng.api.AEApi").getMethod("instance").invoke(null),"registries"),"cell");}
    private static Object items() throws Exception {return Class.forName("appeng.api.storage.StorageChannel").getField("ITEMS").get(null);}
    void create() throws Exception {
        block(7,8,"appliedenergistics2:tile.BlockCableBus");
        block(8,8,"appliedenergistics2:tile.BlockCreativeEnergyCell");
        block(9,8,"appliedenergistics2:tile.BlockDrive");
        Object cable=world.getTileEntity(ox+7,176,oz+8);
        Item parts=(Item)Item.itemRegistry.getObject("appliedenergistics2:item.ItemMultiPart");
        call(cable,"addPart",new ItemStack(parts,1,0),ForgeDirection.UNKNOWN,player);
        call(cable,"addPart",new ItemStack(parts,1,360),ForgeDirection.WEST,player);
        ItemStack cell=new ItemStack((Item)Item.itemRegistry.getObject("appliedenergistics2:item.ItemBasicStorageCell.1k"));
        Object inventory=call(registry(),"getCellInventory",cell,null,items());
        Object source=Class.forName("appeng.api.networking.security.BaseActionSource").getConstructor().newInstance();
        Object actionable=Class.forName("appeng.api.config.Actionable").getField("MODULATE").get(null);
        for(ItemStack stack:List.of(new ItemStack(Items.paper,5000),new ItemStack(Blocks.planks,1024),new ItemStack(Blocks.log,256),
            new ItemStack(Blocks.wool,32,1),new ItemStack(Blocks.wool,32,2),new ItemStack(Items.paper,8).setStackDisplayName("ME Variant A"),new ItemStack(Items.paper,8).setStackDisplayName("ME Variant B"))) {
            Object ae=Class.forName("appeng.util.item.AEItemStack").getMethod("create",ItemStack.class).invoke(null,stack);
            Object remainder=call(inventory,"injectItems",ae,actionable,source);
            if(remainder!=null) throw new IllegalStateException("fixture cell capacity insufficient");
        }
        ((IInventory)call(drive(),"getInternalInventory")).setInventorySlotContents(0,cell);

    }
    Object status() {
        var stacks=new ArrayList<>();
        try {
            Object drive=drive();if(drive==null) return Json.object("available",false);
            ItemStack cell=((IInventory)call(drive,"getInternalInventory")).getStackInSlot(0);
            Object inventory=call(registry(),"getCellInventory",cell,null,items());
            Object list=call(items(),"createList");call(inventory,"getAvailableItems",list);
            for(Object value:(Iterable<?>)list) {
                ItemStack stack=(ItemStack)call(value,"getItemStack");
                stacks.add(Json.object("id",Item.itemRegistry.getNameForObject(stack.getItem()),"meta",stack.getItemDamage(),"count",call(value,"getStackSize"),"name",stack.getDisplayName(),"nbt",stack.hasTagCompound()?stack.getTagCompound().toString():null));
            }
            return Json.object("available",true,"powered",call(drive,"isPowered"),"stored",stacks);
        } catch(Exception failure) {return Json.object("error",failure.toString());}
    }
    private static Object call(Object target,String name,Object...args) throws Exception {
        for(Method method:target.getClass().getMethods()) if(method.getName().equals(name)&&method.getParameterCount()==args.length) try {
            method.setAccessible(true);return method.invoke(target,args);
        } catch(IllegalArgumentException mismatch) {}
        throw new NoSuchMethodException(name);
    }
}
