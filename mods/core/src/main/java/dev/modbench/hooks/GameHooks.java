// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.hooks;

import cpw.mods.fml.relauncher.FMLLaunchHandler;
import net.minecraft.network.INetHandler;
import net.minecraft.network.Packet;

/**
 * Static targets of EventTransformer/ActionTransformer. The core jar is installed on the dedicated server too, where
 * net.minecraft.client does not exist, so this class must not reference it: client work lives in {@link ClientHooks}.
 */
public final class GameHooks {
    private GameHooks() {}
    private static final boolean CLIENT=FMLLaunchHandler.side().isClient();
    public static void world(Object world,boolean post) {if(CLIENT)ClientHooks.world(world,post);}
    public static void chunk(Object world,int x,int z,boolean load,boolean post) {if(CLIENT)ClientHooks.chunk(world,x,z,load,post);}
    public static void block(boolean changed,Object world,int x,int y,int z) {if(CLIENT)ClientHooks.block(changed,world,x,y,z);}
    public static void send(Object manager,Object packet,boolean post) {if(CLIENT)ClientHooks.send(manager,packet,post);}
    public static void receive(Packet packet,INetHandler handler) {if(CLIENT)ClientHooks.receive(packet,handler);else packet.processPacket(handler);}
    public static float yaw(float yaw,Object entity,int jump) {return CLIENT?ClientHooks.yaw(yaw,entity,jump):yaw;}
    public static boolean sprint(Object binding) {return ClientHooks.sprint(binding);}
    public static boolean chat(String message) {return CLIENT&&ClientHooks.chat(message);}
    public static boolean tab(String prefix) {return CLIENT&&ClientHooks.tab(prefix);}
    public static void finishTab() {if(CLIENT)ClientHooks.finishTab();}
    public static void confirmed(Object packet) {if(CLIENT)ClientHooks.confirmed(packet);}
    public static boolean ownsNativeActions() {return CLIENT&&ClientHooks.ownsNativeActions();}
}
