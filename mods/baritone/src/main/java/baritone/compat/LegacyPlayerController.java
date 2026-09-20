// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.compat;

import net.minecraft.client.Minecraft;
public final class LegacyPlayerController {
    private final Minecraft mc=Minecraft.getMinecraft();
    private static final java.lang.reflect.Field HITTING=cpw.mods.fml.relauncher.ReflectionHelper.findField(net.minecraft.client.multiplayer.PlayerControllerMP.class,"isHittingBlock","field_78778_j");
    private static final java.lang.reflect.Field CURRENT_Y=cpw.mods.fml.relauncher.ReflectionHelper.findField(net.minecraft.client.multiplayer.PlayerControllerMP.class,"currentBlockY","field_78772_d");
    private static final java.lang.reflect.Method SYNC=cpw.mods.fml.relauncher.ReflectionHelper.findMethod(net.minecraft.client.multiplayer.PlayerControllerMP.class,null,new String[]{"syncCurrentPlayItem","func_78750_j"});
    public double getBlockReachDistance(){return mc.playerController.getBlockReachDistance();}
    public void resetBlockRemoving(){mc.playerController.resetBlockRemoving();}
    public void syncHeldItem(){try{SYNC.invoke(mc.playerController);}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}}
    public boolean hasBrokenBlock(){try{return CURRENT_Y.getInt(mc.playerController)==-1;}catch(IllegalAccessException e){throw new IllegalStateException(e);}}
    public void setHittingBlock(boolean value){try{HITTING.setBoolean(mc.playerController,value);}catch(IllegalAccessException e){throw new IllegalStateException(e);}}
    public boolean clickBlock(BlockPos p,EnumFacing side){
        if(!permitted(0,p,side))return false;
        mc.playerController.clickBlock(p.getX(),p.getY(),p.getZ(),side.ordinal());return true;
    }
    public boolean onPlayerDamageBlock(BlockPos p,EnumFacing side){
        if(!permitted(0,p,side))return false;
        mc.playerController.onPlayerDamageBlock(p.getX(),p.getY(),p.getZ(),side.ordinal());return true;
    }
    public boolean processRightClickBlock(BlockPos p,EnumFacing side,Vec3d hit){
        if(!permitted(1,p,side))return false;
        var engine=baritone.Baritone.instance();
        if(engine.getBuilderProcess().isActive())engine.getBuilderProcess().beforePlace.accept(p.offset(side));
        if(!ActionHooks.ownsNativeActions())return false;
        // The server must see the same native sneak/facing state used by placement prediction.
        mc.thePlayer.sendMotionUpdates();
        return mc.playerController.onPlayerRightClick(mc.thePlayer,mc.theWorld,mc.thePlayer.getHeldItem(),p.getX(),p.getY(),p.getZ(),side.ordinal(),hit.nativeVector());
    }
    public boolean processRightClick(){
        if(!ActionHooks.ownsNativeActions()||!dev.modbench.control.ClientMemory.blockAction(2,0,0,0,-1))return false;
        return mc.playerController.sendUseItem(mc.thePlayer,mc.theWorld,mc.thePlayer.getHeldItem());
    }
    private boolean permitted(int action,BlockPos p,EnumFacing side){
        return ActionHooks.ownsNativeActions()&&dev.modbench.control.ClientMemory.blockAction(action,p.getX(),p.getY(),p.getZ(),side.ordinal());
    }
    public void swapInventorySlot(int inventorySlot,int hotbar){
        if(!ActionHooks.ownsNativeActions()||mc.thePlayer.openContainer!=mc.thePlayer.inventoryContainer||mc.thePlayer.inventory.getItemStack()!=null)
            throw new IllegalStateException("owned player inventory with empty cursor required");
        for(Object entry:mc.thePlayer.inventoryContainer.inventorySlots){
            net.minecraft.inventory.Slot slot=(net.minecraft.inventory.Slot)entry;
            if(slot.inventory==mc.thePlayer.inventory&&slot.getSlotIndex()==inventorySlot){
                mc.playerController.windowClick(mc.thePlayer.inventoryContainer.windowId,slot.slotNumber,hotbar,2,mc.thePlayer);return;
            }
        }
        throw new IllegalStateException("player inventory slot not found: "+inventorySlot);
    }
}
