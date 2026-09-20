// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.compat;

import baritone.Baritone;
import baritone.api.event.events.*;
import baritone.api.event.events.type.EventState;
import baritone.api.utils.Pair;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.network.*;

/** Native event boundaries. Queued gameplay packets dispatch on the client thread. */
public final class NativeEvents {
    private static final Map<String,LongAdder> counts=new ConcurrentHashMap<>();
    private static boolean worldPending;
    private static Object pendingWorld;
    private static String[] tabCompletions;
    private NativeEvents(){}
    private static boolean ready(){return Baritone.initialized();}
    private static baritone.utils.GameEventHandler bus(){return Baritone.instance().getGameEventHandler();}
    public static void count(String name){counts.computeIfAbsent(name,k->new LongAdder()).increment();}
    public static Map<String,Long> diagnostics(){var out=new TreeMap<String,Long>();counts.forEach((k,v)->out.put(k,v.sum()));return out;}
    private static EventState state(boolean post){return post?EventState.POST:EventState.PRE;}
    public static void world(Object next,boolean post){
        if(!ready())return;
        if(!post){
            worldPending=true;pendingWorld=next;
            var engine=Baritone.instance();var lease=engine.getInputOverrideHandler().lease();
            if(lease!=null&&lease.isActive()){
                engine.getPathingBehavior().forceCancel();engine.getInputOverrideHandler().release();lease.close();
            }
        }else {if(!worldPending)return;worldPending=false;pendingWorld=null;}
        count("world_"+state(post));bus().onWorldEvent(new WorldEvent((WorldClient)next,state(post)));
    }
    /** Late cancellable world-load mixins may bypass a previously instrumented return. */
    public static void finishWorldTransition(){
        if(worldPending&&Minecraft.getMinecraft().theWorld==pendingWorld)world(pendingWorld,true);
    }
    public static void chunk(Object world,int x,int z,boolean load,boolean post){
        if(!ready()||world!=Minecraft.getMinecraft().theWorld)return;
        count("chunk_"+state(post));bus().onChunkEvent(new ChunkEvent(state(post),load?ChunkEvent.Type.LOAD:ChunkEvent.Type.UNLOAD,x,z));
    }
    public static void block(boolean changed,Object world,int x,int y,int z){
        if(!changed||!ready()||world!=Minecraft.getMinecraft().theWorld)return;
        count("block_change");World view=new World((WorldClient)world);BlockPos pos=new BlockPos(x,y,z);
        bus().onBlockChange(new BlockChangeEvent(new ChunkPos(x>>4,z>>4),List.of(new Pair<>(pos,view.getBlockState(pos)))));
    }
    public static void send(Object manager,Object packet,boolean post){
        if(!ready())return;var mc=Minecraft.getMinecraft();
        // Server/local Netty callbacks do not own this engine or its game state.
        if(!mc.func_152345_ab()||mc.getNetHandler()==null||manager!=mc.getNetHandler().getNetworkManager())return;
        count("send_"+state(post));bus().onSendPacket(new PacketEvent((NetworkManager)manager,state(post),(Packet)packet));
    }
    public static void receive(Packet packet,INetHandler handler){
        var mc=Minecraft.getMinecraft();
        boolean dispatch=ready()&&mc.func_152345_ab()&&handler==mc.getNetHandler();
        NetworkManager manager=dispatch?mc.getNetHandler().getNetworkManager():null;
        if(dispatch){count("receive_PRE");bus().onReceivePacket(new PacketEvent(manager,EventState.PRE,packet));populate(packet,false);}
        packet.processPacket(handler);
        if(dispatch){populate(packet,true);count("receive_POST");bus().onReceivePacket(new PacketEvent(manager,EventState.POST,packet));}
    }
    private static void populate(Packet packet,boolean post){
        if(packet instanceof net.minecraft.network.play.server.S21PacketChunkData chunk){
            // A full update with an empty section mask is an unload packet.
            if(chunk.func_149274_i()&&chunk.func_149276_g()==0)return;
            populated(chunk.func_149273_e(),chunk.func_149271_f(),chunk.func_149274_i(),post);
        }else if(packet instanceof net.minecraft.network.play.server.S26PacketMapChunkBulk bulk){
            for(int i=0;i<bulk.func_149254_d();i++)populated(bulk.func_149255_a(i),bulk.func_149253_b(i),true,post);
        }
    }
    private static void populated(int x,int z,boolean full,boolean post){
        count("populate_"+state(post));bus().onChunkEvent(new ChunkEvent(state(post),full?ChunkEvent.Type.POPULATE_FULL:ChunkEvent.Type.POPULATE_PARTIAL,x,z));
    }
    public static float yaw(float original,Object entity,int jump){
        if(!ready()||entity!=Minecraft.getMinecraft().thePlayer)return original;
        count(jump==0?"move_rotation":"jump_rotation");
        var player=Minecraft.getMinecraft().thePlayer;
        var event=new RotationMoveEvent(jump==0?RotationMoveEvent.Type.MOTION_UPDATE:RotationMoveEvent.Type.JUMP,original,player.rotationPitch);
        bus().onPlayerRotationMove(event);return event.getYaw();
    }
    public static boolean sprint(net.minecraft.client.settings.KeyBinding binding){
        if(ready()&&binding==Minecraft.getMinecraft().gameSettings.keyBindSprint){
            count("sprint");SprintStateEvent event=new SprintStateEvent();bus().onPlayerSprintState(event);
            if(event.getState()!=null)return event.getState();
        }
        return binding.getIsKeyPressed();
    }
    public static boolean chat(String message){
        if(!ready())return false;count("chat");ChatEvent event=new ChatEvent(message);bus().onSendChatMessage(event);return event.isCancelled();
    }
    public static boolean tab(String prefix){
        if(!ready())return false;count("tab");TabCompleteEvent event=new TabCompleteEvent(prefix);bus().onPreTabComplete(event);
        tabCompletions=event.isCancelled()||event.completions==null?null:event.completions.clone();
        return event.isCancelled();
    }
    public static void finishTab(){
        if(tabCompletions!=null){net.minecraftforge.client.ClientCommandHandler.instance.latestAutoComplete=tabCompletions;tabCompletions=null;}
    }
}
