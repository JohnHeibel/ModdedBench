// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.compat;

import dev.modbench.control.ClientControls;
import dev.modbench.control.api.ClickReceipt;
import dev.modbench.control.api.InputArbiter;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.server.S32PacketConfirmTransaction;

/** One ordinary native swap, with an owned screen and authoritative transaction acknowledgement. */
public final class LegacyInventorySwap {
    public enum Outcome { WAITING, COMPLETE, REPLAN }
    private static LegacyInventorySwap active;
    private static final java.lang.reflect.Field TRANSACTION=cpw.mods.fml.relauncher.ReflectionHelper.findField(net.minecraft.inventory.Container.class,"transactionID","field_75150_e");
    private final Minecraft mc=Minecraft.getMinecraft();
    private final ClientControls.InventorySession screen;
    private final ClickReceipt receipt=new ClickReceipt();
    private final int source,hotbar;
    private final ItemStack wanted,displaced;
    private int age;
    private boolean sent;
    public LegacyInventorySwap(InputArbiter.Lease lease,int source,int hotbar){
        if(active!=null)throw new IllegalStateException("an inventory swap is already pending");
        this.source=source;this.hotbar=hotbar;
        wanted=copy(mc.thePlayer.inventory.getStackInSlot(source));displaced=copy(mc.thePlayer.inventory.getStackInSlot(hotbar));
        screen=ClientControls.beginPlayerInventory(lease);active=this;
    }
    private static ItemStack copy(ItemStack stack){return stack==null?null:stack.copy();}
    public static void confirmed(Object packet){
        if(active!=null&&packet instanceof S32PacketConfirmTransaction p)active.receipt.received(p.func_148889_c(),p.func_148890_d(),p.func_148888_e());
    }
    public Outcome tick(){
        if(!screen.isOpen())throw new IllegalStateException("inventory screen changed during swap");
        if(++age<=2)return Outcome.WAITING;
        if(!sent){
            if(!ItemStack.areItemStacksEqual(wanted,mc.thePlayer.inventory.getStackInSlot(source))||!ItemStack.areItemStacksEqual(displaced,mc.thePlayer.inventory.getStackInSlot(hotbar))){
                // Pickups, mod ticks and late placement acknowledgements can
                // legitimately change a stack while the inventory opens. No
                // click has been sent: close and let the source process select
                // again from current stacks instead of replaying stale intent.
                close();return Outcome.REPLAN;
            }
            new LegacyPlayerController().swapInventorySlot(source,hotbar);
            try{receipt.sent(mc.thePlayer.inventoryContainer.windowId,TRANSACTION.getShort(mc.thePlayer.inventoryContainer));}
            catch(IllegalAccessException e){throw new IllegalStateException(e);}
            sent=true;return Outcome.WAITING;
        }
        if(receipt.rejected())throw new IllegalStateException("server rejected inventory swap; inspect synchronized inventory");
        if(receipt.accepted()&&ItemStack.areItemStacksEqual(wanted,mc.thePlayer.inventory.getStackInSlot(hotbar))&&ItemStack.areItemStacksEqual(displaced,mc.thePlayer.inventory.getStackInSlot(source))){close();return Outcome.COMPLETE;}
        if(age>100)throw new IllegalStateException("inventory swap acknowledgement or synchronized stacks timed out: "+receipt.status());
        return Outcome.WAITING;
    }
    public void close(){if(active==this)active=null;screen.close();}
}
