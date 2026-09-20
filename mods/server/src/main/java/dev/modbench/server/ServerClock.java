// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
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
import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/** Dedicated-server simulation gate. All policy changes and deferred gameplay run on the server thread. */
public final class ServerClock implements ClockHooks.Driver {
    public static final String CHANNEL="MB|Clock";
    private record Incoming(Packet packet, NetHandlerPlayServer handler) {}
    private final MinecraftServer server;
    private final ServerRuntime runtime;
    private final SimulationClock clock=new SimulationClock();
    private final ConcurrentLinkedQueue<Incoming> messages=new ConcurrentLinkedQueue<>();
    private final ArrayDeque<Incoming> deferred=new ArrayDeque<>();
    private NetHandlerPlayServer client;
    private long lastHeartbeat, lastBroadcast, generation;
    private boolean clientPaused, boundaryPaused;
    private volatile boolean simulating;
    private String lastMode="";
    private Consumer<JsonObject> completion;
    private Request owner;
    private long operationDeadline;
    private int fixtureTicks;

    public ServerClock(MinecraftServer server, ServerRuntime runtime) { this.server=server;this.runtime=runtime; }
    private boolean connected() { return client!=null && client.netManager.isChannelOpen(); }
    private boolean settled() { return boundaryPaused && AsyncPause.GREGTECH.ready() && ComputerPause.ready() && (!connected() || clientPaused); }
    public JsonObject status() {
        JsonObject out=clock.status();
        if(clock.paused() && !settled()) out.addProperty("mode",ComputerPause.failure()==null?"pausing":"pause_error");
        out.addProperty("worldId",WorldIdentity.get(server.worldServers[0]));
        out.addProperty("generation",generation);
        out.add("computers",Json.GSON.toJsonTree(ComputerPause.status()));
        out.add("gregtechUpdates",Json.GSON.toJsonTree(AsyncPause.GREGTECH.status()));
        out.addProperty("stepping",false);
        out.addProperty("clientConnected",connected());
        out.addProperty("clientPaused",clientPaused);
        out.addProperty("deferredPackets",deferred.size());
        out.addProperty("scope","dedicated_server_all_dimensions");
        return out;
    }
    public Object command(Request r) {
        Consumer<JsonObject> reply=value->{if(value.has("error")) r.fail("clock_error",value.get("error").getAsString());else r.reply(value);};
        command(r.method,r.params,reply);
        if(completion==reply) owner=r;
        return null;
    }
    private void command(String method, JsonObject params, Consumer<JsonObject> reply) {
        try {
            switch(method) {
                case "time.status" -> { reply.accept(status());return; }
                case "time.configure" -> clock.configure(params);
                case "time.report_failure" -> { if(clock.actionFailed()) requestPause("action_failed"); }
                case "time.pause" -> {
                    String reason=Json.string(params,"reason","requested_pause");
                    if(reason.isBlank()||reason.length()>256) throw new IllegalArgumentException("pause reason must contain 1..256 characters");
                    interrupt("superseded");clock.pause(reason);syncBoundary();
                    completion=reply;
                    operationDeadline=System.nanoTime()+Json.integer(params,"_timeout_ms",30000,1,3600000)*1_000_000L;
                    broadcast(true);return;
                }
                case "time.resume" -> {
                    if(clock.paused()) {
                        syncBoundary();
                        if(!settled()) throw new IllegalArgumentException("pause has not settled; inspect time.status before resuming");
                        ComputerPause.resume();AsyncPause.GREGTECH.resume();
                    }
                    interrupt("superseded");clock.resume();boundaryPaused=false;clientPaused=false;fixtureTicks=0;
                }
                default -> throw new IllegalArgumentException("unknown time method");
            }
            syncBoundary();broadcast(true);reply.accept(status());
        } catch(IllegalArgumentException error) { reply.accept(Json.object("error",error.getMessage())); }
    }
    private void interrupt(String reason) {
        if(completion!=null) { Consumer<JsonObject> previous=completion;completion=null;owner=null;
            previous.accept(Json.object("error",reason)); }
    }
    private void requestPause(String reason) { clock.pause(reason); }
    private void syncBoundary() {
        if(clock.paused() && !boundaryPaused) {
            boundaryPaused=true;clientPaused=false;generation++;AsyncPause.GREGTECH.begin();ComputerPause.begin();
        }
    }
    /** Development-only workload windows; no client lockstep or public stepping contract. */
    void runForFixture(int ticks) {
        if(ticks<1 || ticks>2000 || !settled()) throw new IllegalArgumentException("fixture window requires settled pause and 1..2000 ticks");
        command("time.resume",new JsonObject(),result->{
            if(result.has("error")) throw new IllegalArgumentException(result.get("error").getAsString());
        });
        fixtureTicks=ticks;
    }
    private void broadcast(boolean force) {
        syncBoundary();
        String mode=status().get("mode").getAsString();
        long now=System.nanoTime();
        if(force || !mode.equals(lastMode) || now-lastBroadcast>1_000_000_000L) {
            lastMode=mode;lastBroadcast=now;
            send(Json.object("type","state","state",status()));
        }
    }
    private void send(JsonObject message) {
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
        if(clock.paused() && !simulating && !(packet instanceof C00PacketKeepAlive)) {
            // Forge must finish the login handshake even when reconnecting to a frozen world.
            if(packet instanceof cpw.mods.fml.common.network.internal.FMLProxyPacket proxy
                && (proxy.channel().equals("FML|HS") || proxy.channel().equals("REGISTER")
                    || proxy.channel().equals("FML") && proxy.payload().readableBytes()==2
                    && proxy.payload().getByte(proxy.payload().readerIndex())==0)) return false;
            if(messages.size()<4096) messages.add(new Incoming(packet,play));
            else play.netManager.closeChannel(new net.minecraft.util.ChatComponentText("Modbench paused packet queue full"));
            return true;
        }
        return false;
    }
    @Override public void beforeNetwork(Object manager) {
        // Original network stage, after world updates; deferred FIFO precedes new packets on this connection.
        if(!simulating) return;
        var iterator=deferred.iterator();
        while(iterator.hasNext()) {
            Incoming next=iterator.next();
            if(!next.handler.netManager.isChannelOpen()) { iterator.remove();continue; }
            if(next.handler.netManager==manager) { iterator.remove();next.packet.processPacket(next.handler); }
        }
    }
    private void receive() {
        for(int i=0;i<4096;i++) {
            Incoming next=messages.poll();if(next==null) break;
            if(!next.handler.netManager.isChannelOpen()) continue;
            if(next.packet instanceof C17PacketCustomPayload packet && CHANNEL.equals(packet.func_149559_c())) {
                try {
                    JsonObject data=Json.GSON.fromJson(new String(packet.func_149558_e(),StandardCharsets.UTF_8),JsonObject.class);
                    String type=Json.string(data,"type","");
                    if(type.equals("hello")) {
                        if(client!=null && client!=next.handler && client.netManager.isChannelOpen()) continue;
                        client=next.handler;lastHeartbeat=System.nanoTime();clientPaused=false;broadcast(true);
                    } else if(next.handler==client) {
                        switch(type) {
                            case "heartbeat" -> lastHeartbeat=System.nanoTime();
                            case "paused" -> { if(clock.paused() && data.get("generation").getAsLong()==generation) clientPaused=true; }
                            case "agent_lost" -> { if(clock.pauseOnDisconnect()) requestPause("agent_disconnected"); }
                            case "action_failed" -> { if(clock.actionFailed()) requestPause("action_failed"); }
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
                                command(Json.string(data,"method",""),data.getAsJsonObject("params"),
                                    result->send(Json.object("type","reply","id",id,"result",result)));
                            }
                        }
                    }
                } catch(RuntimeException error) { clock.pause("clock_protocol_error"); }
            } else if(deferred.size()<4096) deferred.add(next);
            else { clock.pause("paused_packet_overflow");next.handler.netManager.closeChannel(new net.minecraft.util.ChatComponentText("Modbench paused packet queue full")); }
        }
    }
    @Override public boolean before() {
        simulating=false;
        runtime.service(server);
        if(clock.paused()) {
            // ServerUtilities 2.2.2 pause-when-empty maintenance boundary; vanilla owns keepalives.
            net.minecraftforge.common.chunkio.ChunkIOExecutor.tick();
            server.func_147137_ag().networkTick();
            if(server instanceof net.minecraft.server.dedicated.DedicatedServer dedicated) dedicated.executePendingCommands();
            for(Object entry:server.getConfigurationManager().playerEntityList) FrozenChunks.send((EntityPlayerMP)entry);
        }
        receive();
        deferred.removeIf(packet->!packet.handler.netManager.isChannelOpen());
        if(connected()) clock.observe(client.playerEntity.getHealth(),client.playerEntity.getAir(),client.playerEntity.getFoodStats().getFoodLevel(),client.playerEntity.isBurning());
        if(client!=null && clock.pauseOnDisconnect()) {
            if(!connected()) clock.pause("client_disconnected");
            else if(System.nanoTime()-lastHeartbeat>15_000_000_000L) clock.pause("client_unresponsive");
        }
        syncBoundary();
        if(owner!=null && (owner.isDone() || !owner.session.connected || owner.expired())) interrupt("pause_owner_lost");
        if(completion!=null) {
            if(ComputerPause.failure()!=null) interrupt("computer_pause_failed: "+ComputerPause.failure());
            else if(System.nanoTime()>operationDeadline) interrupt("pause_timeout; simulation remains gated");
            else if(settled()) {
                Consumer<JsonObject> done=completion;completion=null;owner=null;done.accept(status());
            }
        }
        broadcast(false);
        if(clock.paused()) return false;
        simulating=true;runtime.simulationTick();return true;
    }
    @Override public void after() {
        simulating=false;clock.tickFinished();
        if(fixtureTicks>0 && --fixtureTicks==0) clock.pause("fixture_checkpoint");
        if(connected()) clock.observe(client.playerEntity.getHealth(),client.playerEntity.getAir(),client.playerEntity.getFoodStats().getFoodLevel(),client.playerEntity.isBurning());
        broadcast(false);
    }
}
