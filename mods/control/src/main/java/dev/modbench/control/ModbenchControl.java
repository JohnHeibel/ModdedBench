// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
package dev.modbench.control;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.common.MinecraftForge;

/** Owns the Minecraft-specific side of the shared synthetic-input arbiter. */
@Mod(modid = "modbenchcontrol", name = "Modbench Control", version = "0.1.0", acceptableRemoteVersions = "*")
public final class ModbenchControl {
    private Object world;
    private Object player;

    @Mod.EventHandler public void init(FMLInitializationEvent event) {
        if (!event.getSide().isClient()) return;
        FMLCommonHandler.instance().bus().register(this);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void beforeInput(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (world != mc.theWorld || player != mc.thePlayer) {
            world = mc.theWorld;
            player = mc.thePlayer;
            ClientControls.revoke("world_changed");
            return;
        }
        // Death cancels gameplay input, but an explicitly owned screen must
        // remain operable (for example, native respawn). Ownership ends when
        // that screen or player changes; no screen class is special-cased.
        if (mc.thePlayer == null || mc.thePlayer.isDead && !ClientControls.hasOwnedGui()) ClientControls.revoke("player_unavailable");
        else if (!ClientControls.hasOwnedInventory()) {
            if (!mc.inGameHasFocus) ClientControls.revoke("focus_lost");
            else if (mc.currentScreen != null) ClientControls.revoke("gui_open");
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void afterRender(TickEvent.RenderTickEvent event) {
        if(event.phase==TickEvent.Phase.END) dev.modbench.control.api.UiInput.rendered();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void afterInput(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) ClientControls.reapply();
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void guiOpening(GuiOpenEvent event) {
        if (event.gui != null && !ClientControls.ownsInventoryScreen(event.gui)) {
            ClientControls.revoke(ClientControls.heldRegisteredGuiKey() ? "registered_gui_open" : "gui_open");
        }
    }
}
