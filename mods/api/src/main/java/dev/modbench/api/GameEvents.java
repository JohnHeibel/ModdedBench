// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
package dev.modbench.api;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Native client boundaries the core coremod instruments. Minecraft objects are passed untyped. */
public interface GameEvents {
    /** Minecraft.loadWorld; {@code world} may be null when leaving. */
    default void world(Object world,boolean post) {}
    /** WorldClient.doPreChunk for the current world. */
    default void chunk(Object world,int x,int z,boolean load,boolean post) {}
    /** A block in the current world actually changed. */
    default void blockChanged(Object world,int x,int y,int z) {}
    /** NetworkManager.scheduleOutboundPacket on the client thread for the player's own connection. */
    default void packetSent(Object manager,Object packet,boolean post) {}
    /** Client-thread receipt for the player's own NetHandler, around Packet.processPacket. */
    default void packetReceived(Object packet,Object handler,boolean post) {}
    /** Yaw read by Entity.moveFlying / EntityLivingBase.jump for the local player; return the yaw to use. */
    default float yaw(float yaw,Object entity,boolean jump) {return yaw;}
    /** Sprint key state for EntityPlayerSP.onLivingUpdate; null keeps the native key. */
    default Boolean sprint() {return null;}
    /** Return true to swallow the outgoing chat message. */
    default boolean chat(String message) {return false;}
    /** Return true to skip native tab completion. */
    default boolean tabComplete(String prefix) {return false;}
    /** Native local completion finished. */
    default void tabCompleted() {}
    /** S32PacketConfirmTransaction arrived on the client NetHandler. */
    default void transactionConfirmed(Object packet) {}
    /** True suppresses the vanilla attack loop in Minecraft.sendClickBlockToController. */
    default boolean ownsNativeActions() {return false;}

    static void register(GameEvents listener) {Registered.LISTENERS.add(java.util.Objects.requireNonNull(listener));}
    static List<GameEvents> listeners() {return Registered.LISTENERS;}
    final class Registered {
        private static final List<GameEvents> LISTENERS=new CopyOnWriteArrayList<>();
        private Registered() {}
    }
}
