// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.control;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import dev.modbench.api.ControlRegistry;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.common.MinecraftForge;

/** Mod container of the core coremod: registers the control providers and owns the input arbiter's tick side. */
@Mod(modid = "modbenchcore", name = "ModdedBench Core", version = "0.1.0", acceptableRemoteVersions = "*")
public final class ModbenchCore {
    private Object world;
    private Object player;

    @Mod.EventHandler public void init(FMLInitializationEvent event) {
        if (!event.getSide().isClient()) return;
        ControlRegistry.register(ClientControls.INSTANCE,NativeTargeting.INSTANCE,NativePlacement.INSTANCE,ClientMemory.INSTANCE);
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
        if(event.phase==TickEvent.Phase.END) dev.modbench.api.UiInput.rendered();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void afterInput(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) ClientControls.reapply();
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void guiOpening(GuiOpenEvent event) {
        if (event.gui == null) return;
        // The server connector thread opens GuiDisconnected itself; throwing here would strand the player on GuiConnecting.
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        if (!mc.func_152345_ab()) { mc.func_152344_a(() -> ClientControls.revoke("gui_open")); return; }
        if (!ClientControls.ownsInventoryScreen(event.gui)) {
            ClientControls.revoke(ClientControls.heldRegisteredGuiKey() ? "registered_gui_open" : "gui_open");
        }
    }
}
