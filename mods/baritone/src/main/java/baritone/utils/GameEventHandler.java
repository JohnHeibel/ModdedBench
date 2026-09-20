// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.utils;

import baritone.api.event.listener.IGameEventListener;
import baritone.api.event.events.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

/** Ordered event dispatch; Forge supplies ticks and player-update phases. */
public final class GameEventHandler implements baritone.api.event.listener.IEventBus, IGameEventListener {
    private final List<IGameEventListener> listeners=new CopyOnWriteArrayList<>();
    public void registerEventListener(IGameEventListener listener){listeners.add(listener);}
    public void onTick(TickEvent event){for(var l:listeners)l.onTick(event);}
    public void onPostTick(TickEvent event){for(var l:listeners)l.onPostTick(event);}
    public void onPathEvent(PathEvent event){for(var l:listeners)l.onPathEvent(event);}
    public void onPlayerUpdate(PlayerUpdateEvent event){for(var l:listeners)l.onPlayerUpdate(event);}
    public void onSendChatMessage(ChatEvent event){for(var l:listeners)l.onSendChatMessage(event);}
    public void onPreTabComplete(TabCompleteEvent event){for(var l:listeners)l.onPreTabComplete(event);}
    public void onChunkEvent(ChunkEvent event){for(var l:listeners)l.onChunkEvent(event);}
    public void onBlockChange(BlockChangeEvent event){for(var l:listeners)l.onBlockChange(event);}
    public void onRenderPass(RenderEvent event){for(var l:listeners)l.onRenderPass(event);}
    public void onWorldEvent(WorldEvent event){for(var l:listeners)l.onWorldEvent(event);}
    public void onSendPacket(PacketEvent event){for(var l:listeners)l.onSendPacket(event);}
    public void onReceivePacket(PacketEvent event){for(var l:listeners)l.onReceivePacket(event);}
    public void onPlayerRotationMove(RotationMoveEvent event){for(var l:listeners)l.onPlayerRotationMove(event);}
    public void onPlayerSprintState(SprintStateEvent event){for(var l:listeners)l.onPlayerSprintState(event);}
    public void onBlockInteract(BlockInteractEvent event){for(var l:listeners)l.onBlockInteract(event);}
    public void onPlayerDeath(){for(var l:listeners)l.onPlayerDeath();}
}
