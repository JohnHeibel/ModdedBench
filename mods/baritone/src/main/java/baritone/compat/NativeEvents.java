// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.compat;

import baritone.Baritone;
import baritone.api.event.events.*;
import baritone.api.event.events.type.EventState;
import baritone.api.utils.Pair;
import dev.modbench.api.GameEvents;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.network.*;

/** Adapts the core mod's native boundaries to the engine's event bus. Registered by the mod container. */
public final class NativeEvents implements GameEvents {
    private static final Map<String,LongAdder> counts=new ConcurrentHashMap<>();
    private final Baritone engine;
    private boolean worldPending;
    private Object pendingWorld;
    private String[] tabCompletions;
    public NativeEvents(Baritone engine){this.engine=engine;}
    private baritone.utils.GameEventHandler bus(){return engine.getGameEventHandler();}
    public static void count(String name){counts.computeIfAbsent(name,k->new LongAdder()).increment();}
    public static Map<String,Long> diagnostics(){var out=new TreeMap<String,Long>();counts.forEach((k,v)->out.put(k,v.sum()));return out;}
    private static EventState state(boolean post){return post?EventState.POST:EventState.PRE;}
    @Override public void world(Object next,boolean post){
        if(!post){
            worldPending=true;pendingWorld=next;
            var lease=engine.getInputOverrideHandler().lease();
            if(lease!=null&&lease.isActive()){
                engine.getPathingBehavior().forceCancel();engine.getInputOverrideHandler().release();lease.close();
            }
        }else {if(!worldPending)return;worldPending=false;pendingWorld=null;}
        count("world_"+state(post));bus().onWorldEvent(new WorldEvent((WorldClient)next,state(post)));
    }
    /** Late cancellable world-load mixins may bypass a previously instrumented return. */
    public void finishWorldTransition(){
        if(worldPending&&Minecraft.getMinecraft().theWorld==pendingWorld)world(pendingWorld,true);
    }
    @Override public void chunk(Object world,int x,int z,boolean load,boolean post){
        count("chunk_"+state(post));bus().onChunkEvent(new ChunkEvent(state(post),load?ChunkEvent.Type.LOAD:ChunkEvent.Type.UNLOAD,x,z));
    }
    @Override public void blockChanged(Object world,int x,int y,int z){
        count("block_change");World view=new World((WorldClient)world);BlockPos pos=new BlockPos(x,y,z);
        bus().onBlockChange(new BlockChangeEvent(new ChunkPos(x>>4,z>>4),List.of(new Pair<>(pos,view.getBlockState(pos)))));
    }
    @Override public void packetSent(Object manager,Object packet,boolean post){
        count("send_"+state(post));bus().onSendPacket(new PacketEvent((NetworkManager)manager,state(post),(Packet)packet));
    }
    @Override public void packetReceived(Object packet,Object handler,boolean post){
        NetworkManager manager=Minecraft.getMinecraft().getNetHandler().getNetworkManager();
        if(post)populate((Packet)packet,true);
        count("receive_"+state(post));bus().onReceivePacket(new PacketEvent(manager,state(post),(Packet)packet));
        if(!post)populate((Packet)packet,false);
    }
    private void populate(Packet packet,boolean post){
        if(packet instanceof net.minecraft.network.play.server.S21PacketChunkData chunk){
            // A full update with an empty section mask is an unload packet.
            if(chunk.func_149274_i()&&chunk.func_149276_g()==0)return;
            populated(chunk.func_149273_e(),chunk.func_149271_f(),chunk.func_149274_i(),post);
        }else if(packet instanceof net.minecraft.network.play.server.S26PacketMapChunkBulk bulk){
            for(int i=0;i<bulk.func_149254_d();i++)populated(bulk.func_149255_a(i),bulk.func_149253_b(i),true,post);
        }
    }
    private void populated(int x,int z,boolean full,boolean post){
        count("populate_"+state(post));bus().onChunkEvent(new ChunkEvent(state(post),full?ChunkEvent.Type.POPULATE_FULL:ChunkEvent.Type.POPULATE_PARTIAL,x,z));
    }
    @Override public float yaw(float original,Object entity,boolean jump){
        count(jump?"jump_rotation":"move_rotation");
        var player=Minecraft.getMinecraft().thePlayer;
        var event=new RotationMoveEvent(jump?RotationMoveEvent.Type.JUMP:RotationMoveEvent.Type.MOTION_UPDATE,original,player.rotationPitch);
        bus().onPlayerRotationMove(event);return event.getYaw();
    }
    @Override public Boolean sprint(){
        count("sprint");SprintStateEvent event=new SprintStateEvent();bus().onPlayerSprintState(event);return event.getState();
    }
    @Override public boolean chat(String message){
        count("chat");ChatEvent event=new ChatEvent(message);bus().onSendChatMessage(event);return event.isCancelled();
    }
    @Override public boolean tabComplete(String prefix){
        count("tab");TabCompleteEvent event=new TabCompleteEvent(prefix);bus().onPreTabComplete(event);
        tabCompletions=event.isCancelled()||event.completions==null?null:event.completions.clone();
        return event.isCancelled();
    }
    @Override public void tabCompleted(){
        if(tabCompletions!=null){net.minecraftforge.client.ClientCommandHandler.instance.latestAutoComplete=tabCompletions;tabCompletions=null;}
    }
    @Override public void transactionConfirmed(Object packet){
        var swap=engine.pendingSwap;if(swap!=null)swap.confirmed(packet);
    }
    @Override public boolean ownsNativeActions(){return engine.ownsNativeActions();}
}
