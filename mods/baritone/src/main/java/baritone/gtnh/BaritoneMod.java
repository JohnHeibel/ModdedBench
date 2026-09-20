// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import dev.modbench.api.GameEvents;
import dev.modbench.api.NavigationRegistry;

@Mod(modid="modbenchbaritone",name="Baritone GTNH Port",version="0.1.0",dependencies="required-after:modbenchcore",acceptableRemoteVersions="*")
public final class BaritoneMod {
    private BaritoneNavigation navigation;
    private baritone.compat.NativeEvents events;
    @Mod.EventHandler public void init(FMLInitializationEvent event) {
        if(!event.getSide().isClient()) return;
        navigation=new BaritoneNavigation();NavigationRegistry.register(navigation);
        events=new baritone.compat.NativeEvents(navigation.reference());GameEvents.register(events);
        net.minecraftforge.client.ClientCommandHandler.instance.registerCommand(new BaritoneCommand(navigation));
        FMLCommonHandler.instance().bus().register(this);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(this);
    }
    @SubscribeEvent public void tick(TickEvent.ClientTickEvent event) {
        if(event.phase==TickEvent.Phase.START){events.finishWorldTransition();navigation.tick();}
        else navigation.afterTick();
    }
    @SubscribeEvent public void renderTick(TickEvent.RenderTickEvent event){
        if(event.phase==TickEvent.Phase.END)events.finishWorldTransition();
    }
    @SubscribeEvent public void chunkUnload(net.minecraftforge.event.world.ChunkEvent.Unload event){
        if(event.world.isRemote&&navigation!=null)navigation.reference().getWorldProvider().captureUnloading(event.getChunk());
    }
    @SubscribeEvent public void render(net.minecraftforge.client.event.RenderWorldLastEvent event){
        if(net.minecraft.client.Minecraft.getMinecraft().theWorld==null)return;
        baritone.compat.NativeEvents.count("render");
        navigation.reference().getGameEventHandler().onRenderPass(new baritone.api.event.events.RenderEvent(event.partialTicks));
    }
    @SubscribeEvent public void gui(net.minecraftforge.client.event.GuiOpenEvent event){
        if(event.gui instanceof net.minecraft.client.gui.GuiGameOver){
            baritone.compat.NativeEvents.count("death");navigation.reference().getGameEventHandler().onPlayerDeath();
        }
    }
    @SubscribeEvent public void interact(net.minecraftforge.event.entity.player.PlayerInteractEvent event){
        if(event.entityPlayer!=net.minecraft.client.Minecraft.getMinecraft().thePlayer)return;
        if(event.action!=net.minecraftforge.event.entity.player.PlayerInteractEvent.Action.LEFT_CLICK_BLOCK
            &&event.action!=net.minecraftforge.event.entity.player.PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK)return;
        var type=event.action==net.minecraftforge.event.entity.player.PlayerInteractEvent.Action.LEFT_CLICK_BLOCK
            ?baritone.api.event.events.BlockInteractEvent.Type.START_BREAK:baritone.api.event.events.BlockInteractEvent.Type.USE;
        baritone.compat.NativeEvents.count("block_interact");
        navigation.reference().getGameEventHandler().onBlockInteract(new baritone.api.event.events.BlockInteractEvent(new baritone.compat.BlockPos(event.x,event.y,event.z),type));
    }
}
