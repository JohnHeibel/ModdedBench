// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.server;

import com.google.gson.JsonObject;
import dev.modbench.bridge.*;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.*;
import net.minecraft.network.play.client.C00PacketKeepAlive;
import net.minecraft.network.play.client.C17PacketCustomPayload;
import net.minecraft.network.play.server.S3FPacketCustomPayload;
import net.minecraft.server.MinecraftServer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Dedicated-server simulation gate: Minecraft I/O around {@link PauseCoordinator}. All policy changes and deferred gameplay run on the server thread. */
public final class ServerClock implements ClockHooks.Driver, PauseCoordinator.Host {
    public static final String CHANNEL="MB|Clock";
    private record Incoming(Packet packet, NetHandlerPlayServer handler) implements PauseCoordinator.Deferred {
        @Override public boolean open() { return handler.netManager.isChannelOpen(); }
        @Override public Object connection() { return handler.netManager; }
        @Override public void process() { packet.processPacket(handler); }
    }
    private final MinecraftServer server;
    private final ServerRuntime runtime;
    private final PauseCoordinator coordinator=new PauseCoordinator(new SimulationClock(),AsyncPause.GREGTECH,ComputerPause.BARRIER,this);
    private final SimulationClock clock=coordinator.clock;
    private final ConcurrentLinkedQueue<Incoming> messages=new ConcurrentLinkedQueue<>();
    /** The operator console creates this file in the server directory, which no agent can reach. */
    private static final java.io.File HOLD=new java.io.File("modbench-hold");
    private NetHandlerPlayServer client;
    private long lastHeartbeat;

    public ServerClock(MinecraftServer server, ServerRuntime runtime) { this.server=server;this.runtime=runtime; }
    private boolean connected() { return client!=null && client.netManager.isChannelOpen(); }
    @Override public boolean clientConnected() { return connected(); }
    @Override public void decorate(JsonObject status) { status.addProperty("worldId",WorldIdentity.get(server.worldServers[0])); }
    public JsonObject status() { return coordinator.status(); }
    public Object command(Request r) { return coordinator.command(r); }
    /** Development-only workload windows; no client lockstep or public stepping contract. */
    void runForFixture(int ticks) { coordinator.runForFixture(ticks); }
    @Override public void send(JsonObject message) {
        if(client!=null && client.netManager.isChannelOpen()) client.sendPacket(new S3FPacketCustomPayload(CHANNEL,
            Json.GSON.toJson(message).getBytes(StandardCharsets.UTF_8)));
    }
    @Override public boolean packet(Object value, Object handler) {
        if(!(handler instanceof NetHandlerPlayServer play)) return false;
        Packet packet=(Packet)value;
        if(packet instanceof C17PacketCustomPayload payload && CHANNEL.equals(payload.func_149559_c())) {
            if(messages.size()<1024) messages.add(new Incoming(packet,play));
            return true;
        }
        if(coordinator.deferring() && !(packet instanceof C00PacketKeepAlive)) {
            // Forge must finish the login handshake even when reconnecting to a frozen world.
            if(packet instanceof cpw.mods.fml.common.network.internal.FMLProxyPacket proxy
                && (proxy.channel().equals("FML|HS") || proxy.channel().equals("REGISTER")
                    || proxy.channel().equals("FML") && proxy.payload().readableBytes()==2
                    && proxy.payload().getByte(proxy.payload().readerIndex())==0)) return false;
            if(messages.size()<4096) messages.add(new Incoming(packet,play));
            else play.netManager.closeChannel(new net.minecraft.util.ChatComponentText("ModdedBench paused packet queue full"));
            return true;
        }
        return false;
    }
    @Override public void beforeNetwork(Object manager) { coordinator.replay(manager); }
    private void receive() {
        for(int i=0;i<4096;i++) {
            Incoming next=messages.poll();if(next==null) break;
            if(!next.open()) continue;
            if(next.packet instanceof C17PacketCustomPayload packet && CHANNEL.equals(packet.func_149559_c())) {
                try {
                    JsonObject data=Json.GSON.fromJson(new String(packet.func_149558_e(),StandardCharsets.UTF_8),JsonObject.class);
                    String type=Json.string(data,"type","");
                    if(type.equals("hello")) {
                        if(client!=null && client!=next.handler && client.netManager.isChannelOpen()) continue;
                        client=next.handler;lastHeartbeat=System.nanoTime();coordinator.clientHello();
                    } else if(next.handler==client) {
                        switch(type) {
                            case "heartbeat" -> lastHeartbeat=System.nanoTime();
                            case "paused" -> coordinator.clientPaused(data.get("generation").getAsLong());
                            case "agent_lost" -> { if(clock.pauseOnDisconnect()) clock.pause("agent_disconnected"); }
                            case "action_failed" -> { if(clock.actionFailed()) clock.pause("action_failed"); }
                            case "entity_observation" -> {
                                JsonObject result;
                                try {result=EntityObservation.read(client.playerEntity,data.getAsJsonObject("params"));}
                                catch(IllegalArgumentException error) {result=Json.object("error",error.getMessage());}
                                send(Json.object("type","reply","id",data.get("id").getAsString(),"result",result));
                            }
                            case "observations" -> {
                                String id=data.get("id").getAsString();JsonObject result;
                                try {result=runtime.observations.batch(client.playerEntity,data.getAsJsonObject("params"));}
                                catch(Exception|LinkageError error){result=Json.object("error",TileInterfaces.error(error));}
                                java.util.List<JsonObject> frames;
                                try{frames=ObservationFrames.encode(id,result);}
                                catch(IllegalArgumentException error){frames=ObservationFrames.encode(id,Json.object("error",error.getMessage()+"; reduce batch, inventory limit or NBT budget"));}
                                for(JsonObject frame:frames)send(frame);
                            }
                            case "command" -> {
                                String id=data.get("id").getAsString();
                                coordinator.command(Json.string(data,"method",""),data.getAsJsonObject("params"),
                                    result->send(Json.object("type","reply","id",id,"result",result)));
                            }
                        }
                    }
                } catch(RuntimeException error) { clock.pause("clock_protocol_error"); }
            } else if(!coordinator.defer(next)) next.handler.netManager.closeChannel(new net.minecraft.util.ChatComponentText("ModdedBench paused packet queue full"));
        }
    }
    private void observe() {
        if(connected()) clock.observe(client.playerEntity.getHealth(),client.playerEntity.getAir(),client.playerEntity.getFoodStats().getFoodLevel(),client.playerEntity.isBurning());
    }
    @Override public boolean before() {
        runtime.service(server);
        if(clock.paused()) {
            // ServerUtilities 2.2.2 pause-when-empty maintenance boundary; vanilla owns keepalives.
            net.minecraftforge.common.chunkio.ChunkIOExecutor.tick();
            server.func_147137_ag().networkTick();
            if(server instanceof net.minecraft.server.dedicated.DedicatedServer dedicated) dedicated.executePendingCommands();
            for(Object entry:server.getConfigurationManager().playerEntityList) FrozenChunks.send((EntityPlayerMP)entry);
        }
        receive();
        observe();
        if(client!=null && clock.pauseOnDisconnect()) {
            if(!connected()) clock.pause("client_disconnected");
            else if(System.nanoTime()-lastHeartbeat>15_000_000_000L) clock.pause("client_unresponsive");
        }
        coordinator.hold(HOLD.exists());
        if(!coordinator.before()) return false;
        runtime.simulationTick();return true;
    }
    @Override public void after() {
        coordinator.after();
        observe();
        coordinator.broadcast(false);
    }
}
