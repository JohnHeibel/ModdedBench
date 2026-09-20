// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.control;

import dev.modbench.api.BlockAttackGuard;
import dev.modbench.api.Controls;
import dev.modbench.api.InputArbiter;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.KeyBinding;

/** Client-side access point shared by the bridge and navigation mods. */
public final class ClientControls implements Controls {
    static final ClientControls INSTANCE=new ClientControls();
    private static final Minecraft MC = Minecraft.getMinecraft();
    private static final MinecraftSink SINK = new MinecraftSink();
    private static final InputArbiter ARBITER = new InputArbiter(SINK);
    private static final Set<Integer> GUI_KEYS = new LinkedHashSet<Integer>();
    private static InventorySession inventorySession;
    private static InputArbiter.Lease guiLease;
    private static net.minecraft.client.gui.GuiScreen guiScreen;
    private static InputArbiter.Lease attackLease;
    private static BlockAttackGuard attackGuard;
    private static net.minecraft.world.World attackWorld;

    /** Capture the actual ray before a raw attack hold. No target means no block edits. */
    public void guardBlockAttack(InputArbiter.Lease lease) {
        requireGameThread();attackLease=lease;attackWorld=MC.theWorld;
        var hit=NativeTargeting.INSTANCE.refresh();
        attackGuard=hit!=null&&hit.typeOfHit==net.minecraft.util.MovingObjectPosition.MovingObjectType.BLOCK
            ?new BlockAttackGuard(hit.blockX,hit.blockY,hit.blockZ,MC.theWorld.getBlock(hit.blockX,hit.blockY,hit.blockZ),MC.theWorld.getBlockMetadata(hit.blockX,hit.blockY,hit.blockZ)):null;
    }
    public boolean blockAttackChanged(InputArbiter.Lease lease) {
        requireGameThread();
        return attackLease==lease&&attackGuard!=null&&(MC.theWorld!=attackWorld||!attackGuard.unchanged(
            MC.theWorld.getBlock(attackGuard.x(),attackGuard.y(),attackGuard.z()),MC.theWorld.getBlockMetadata(attackGuard.x(),attackGuard.y(),attackGuard.z())));
    }
    public void releaseBlockAttack(InputArbiter.Lease lease) {
        requireGameThread();
        if(attackLease==lease){attackLease=null;attackGuard=null;attackWorld=null;}
    }
    public static boolean allowBlockAttack(int x,int y,int z) {
        requireGameThread();
        if(attackLease==null||!attackLease.isActive())return true;
        return MC.theWorld==attackWorld&&attackGuard!=null&&attackGuard.permits(x,y,z,
            MC.theWorld.getBlock(x,y,z),MC.theWorld.getBlockMetadata(x,y,z));
    }

    private ClientControls() { }

    /** Synthetic input needs logical game focus even when the desktop window is in the background. */
    public void focusForInput() {
        requireGameThread();
        MC.displayGuiScreen(null);
        MC.inGameHasFocus=true;
    }

    public InputArbiter arbiter() {
        requireGameThread();
        return ARBITER;
    }

    /** Brief owned inventory screen. No movement is issued while the container initializes/synchronizes. */
    public InventorySession beginPlayerInventory(InputArbiter.Lease lease) {
        requireGameThread();
        if(!lease.isActive() || MC.thePlayer==null || MC.currentScreen!=null || inventorySession!=null
            || MC.thePlayer.openContainer!=MC.thePlayer.inventoryContainer || MC.thePlayer.inventory.getItemStack()!=null)
            throw new IllegalArgumentException("closed_player_inventory_and_active_lease_required");
        lease.setKeys(Collections.emptySet());
        InventorySession session=new InventorySession(lease);
        inventorySession=session;
        MC.displayGuiScreen(session.screen);
        if(!session.isOpen()) {session.close();throw new IllegalArgumentException("inventory_screen_changed");}
        return session;
    }

    public static final class InventorySession implements Controls.InventorySession {
        private final InputArbiter.Lease lease;
        private final EntityPlayerSP player=MC.thePlayer;
        private final net.minecraft.client.gui.inventory.GuiInventory screen=new net.minecraft.client.gui.inventory.GuiInventory(player);
        private InventorySession(InputArbiter.Lease lease) {this.lease=lease;}
        public boolean isOpen() {
            requireGameThread();
            return inventorySession==this && lease.isActive() && MC.thePlayer==player && MC.currentScreen==screen;
        }
        @Override public void close() {
            requireGameThread();
            if(inventorySession!=this) return;
            inventorySession=null;
            // Preserve a nonempty cursor for explicit recovery rather than dropping its item.
            if(MC.currentScreen==screen && MC.thePlayer==player && player.inventory.getItemStack()==null) {
                player.closeScreen();INSTANCE.focusForInput();
            }
            if(MC.currentScreen!=null) revoke("inventory_operation_incomplete");
            else reapply();
        }
    }

    static boolean ownsInventoryScreen(net.minecraft.client.gui.GuiScreen screen) {
        return inventorySession!=null && screen==inventorySession.screen && inventorySession.lease.isActive();
    }
    public boolean ownsPlayerInventory(InputArbiter.Lease lease) {
        requireGameThread();
        return inventorySession!=null && inventorySession.lease==lease && inventorySession.isOpen();
    }
    static boolean hasOwnedInventory() {
        return inventorySession!=null && inventorySession.isOpen() || hasOwnedGui();
    }
    static boolean hasOwnedGui() {
        return guiLease!=null && guiLease.isActive() && MC.currentScreen==guiScreen;
    }
    public void ownGui(InputArbiter.Lease lease,Object screen) {
        requireGameThread();if(!lease.isActive()||screen==null||screen!=MC.currentScreen) throw new IllegalArgumentException("current GUI and active lease required");
        guiLease=lease;guiScreen=(net.minecraft.client.gui.GuiScreen)screen;
    }
    public void releaseGui(InputArbiter.Lease lease) {
        requireGameThread();if(guiLease==lease) {guiLease=null;guiScreen=null;}
    }

    /**
     * Marks a binding as intentionally allowed to open a GUI while synthetic
     * input owns it. Opening that GUI still ends the current lease.
     */
    public static void registerGuiKey(int keyCode) {
        requireGameThread();
        GUI_KEYS.add(keyCode);
    }

    public static void registerGuiKey(KeyBinding key) {
        if (key == null) throw new NullPointerException("key");
        registerGuiKey(key.getKeyCode());
    }

    static void revoke(String reason) {
        requireGameThread();
        if(ARBITER.current().active()) cpw.mods.fml.common.FMLLog.warning("ModdedBench input revoked: %s; focus=%s; screen=%s; ownedInventory=%s; ownedGui=%s",reason,MC.inGameHasFocus,MC.currentScreen==null?"none":MC.currentScreen.getClass().getName(),inventorySession!=null&&inventorySession.isOpen(),hasOwnedGui());
        ARBITER.revoke(reason);
    }

    static void reapply() {
        requireGameThread();
        ARBITER.reapply();
    }

    static boolean heldRegisteredGuiKey() {
        return SINK.heldRegisteredGuiKey();
    }

    static void requireGameThread() {
        if (!MC.func_152345_ab()) throw new IllegalStateException("ClientControls must be used on the Minecraft game thread");
    }

    private static final class MinecraftSink implements InputArbiter.Sink {
        private Set<Integer> held = Collections.emptySet();

        @Override public void applyKeys(Set<Integer> requested) {
            requireGameThread();
            Set<Integer> copy = new LinkedHashSet<Integer>(requested);
            for (Integer code : held) if (!copy.contains(code)) {
                KeyBinding.setKeyBindState(code, false);
                // A cancelled tap must not leave a queued click/GUI action behind.
                for (KeyBinding binding : MC.gameSettings.keyBindings) {
                    if (binding.getKeyCode()==code) while(binding.isPressed()) { }
                }
            }
            for (Integer code : copy) {
                boolean newlyHeld = !held.contains(code);
                KeyBinding.setKeyBindState(code, true);
                if (newlyHeld) KeyBinding.onTick(code);
            }
            held = Collections.unmodifiableSet(copy);
        }

        @Override public void applyLook(float yaw, float pitch) {
            requireGameThread();
            EntityPlayerSP player = MC.thePlayer;
            if (player != null) {
                player.rotationYaw = yaw;
                player.rotationPitch = pitch;
            }
        }

        private boolean heldRegisteredGuiKey() {
            for (Integer code : held) if (GUI_KEYS.contains(code)) return true;
            return false;
        }
    }
}
