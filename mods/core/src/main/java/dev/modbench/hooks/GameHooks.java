// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
package dev.modbench.hooks;

import dev.modbench.api.GameEvents;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.network.INetHandler;
import net.minecraft.network.Packet;

/** Static targets of EventTransformer/ActionTransformer; fans out to registered {@link GameEvents}. */
public final class GameHooks {
    private GameHooks() {}
    private static List<GameEvents> listeners() {return GameEvents.listeners();}
    public static void world(Object world,boolean post) {for(var l:listeners())l.world(world,post);}
    public static void chunk(Object world,int x,int z,boolean load,boolean post) {
        if(world!=Minecraft.getMinecraft().theWorld)return;
        for(var l:listeners())l.chunk(world,x,z,load,post);
    }
    public static void block(boolean changed,Object world,int x,int y,int z) {
        if(!changed||world!=Minecraft.getMinecraft().theWorld)return;
        for(var l:listeners())l.blockChanged(world,x,y,z);
    }
    public static void send(Object manager,Object packet,boolean post) {
        var mc=Minecraft.getMinecraft();
        // Server/local Netty callbacks do not own the client's game state.
        if(!mc.func_152345_ab()||mc.getNetHandler()==null||manager!=mc.getNetHandler().getNetworkManager())return;
        for(var l:listeners())l.packetSent(manager,packet,post);
    }
    public static void receive(Packet packet,INetHandler handler) {
        var mc=Minecraft.getMinecraft();
        boolean own=mc.func_152345_ab()&&handler==mc.getNetHandler();
        if(own)for(var l:listeners())l.packetReceived(packet,handler,false);
        packet.processPacket(handler);
        if(own)for(var l:listeners())l.packetReceived(packet,handler,true);
    }
    public static float yaw(float yaw,Object entity,int jump) {
        if(entity!=Minecraft.getMinecraft().thePlayer)return yaw;
        for(var l:listeners())yaw=l.yaw(yaw,entity,jump!=0);
        return yaw;
    }
    public static boolean sprint(KeyBinding binding) {
        if(binding==Minecraft.getMinecraft().gameSettings.keyBindSprint)for(var l:listeners()){Boolean state=l.sprint();if(state!=null)return state;}
        return binding.getIsKeyPressed();
    }
    public static boolean chat(String message) {boolean cancel=false;for(var l:listeners())cancel|=l.chat(message);return cancel;}
    public static boolean tab(String prefix) {boolean cancel=false;for(var l:listeners())cancel|=l.tabComplete(prefix);return cancel;}
    public static void finishTab() {for(var l:listeners())l.tabCompleted();}
    public static void confirmed(Object packet) {for(var l:listeners())l.transactionConfirmed(packet);}
    public static boolean ownsNativeActions() {for(var l:listeners())if(l.ownsNativeActions())return true;return false;}
}
