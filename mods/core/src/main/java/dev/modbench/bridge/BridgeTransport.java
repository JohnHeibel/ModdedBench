// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
package dev.modbench.bridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.codec.http.websocketx.WebSocket08FrameDecoder;
import io.netty.handler.codec.http.websocketx.WebSocket13FrameDecoder;
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator;
import io.netty.util.concurrent.DefaultThreadFactory;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Loopback-only authenticated endpoint, compatible with the existing Python Kernel. No Baritone dependency. */
public final class BridgeTransport implements AutoCloseable {
    private final BridgeRuntime runtime;
    private final String token = UUID.randomUUID().toString() + UUID.randomUUID();
    private final Set<Channel> clients = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private NioEventLoopGroup group;
    private Channel listener;

    public BridgeTransport(BridgeRuntime runtime) { this.runtime = runtime; }

    public void start(int defaultPort) throws Exception {
        runtime.seal();
        int requested = Integer.getInteger("modbench.port", defaultPort);
        if (requested < 0 || requested > 65535) throw new IllegalArgumentException("invalid modbench.port");
        group = new NioEventLoopGroup(1, new DefaultThreadFactory("modbench-" + runtime.side(), true));
        try {
            listener = new ServerBootstrap().group(group).channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new HttpServerCodec(), new HttpObjectAggregator(1 << 20),
                            new WebSocketServerProtocolHandler("/ws", null, false), new WebSocketFrameAggregator(8 << 20), new Handler());
                    }
                }).bind(new InetSocketAddress("127.0.0.1", requested)).sync().channel();
            int port = port(); // 0 requested an ephemeral port (tests)
            Path tokenPath = Path.of(System.getProperty("modbench.tokenFile",
                System.getProperty("user.home") + "/.moddedbench/bridge-" + port + ".token")).toAbsolutePath();
            Files.createDirectories(tokenPath.getParent());
            Files.writeString(tokenPath, token, StandardCharsets.UTF_8);
            tokenPath.toFile().setReadable(false, false);
            tokenPath.toFile().setReadable(true, true);
            tokenPath.toFile().setWritable(false, false);
            tokenPath.toFile().setWritable(true, true);
            System.out.println("[Modbench] " + runtime.side() + " bridge ready at ws://127.0.0.1:" + port + "/ws");
        } catch (Exception e) { close(); throw e; }
    }

    /** Bound port; only meaningful after {@link #start(int)}. */
    public int port() { return ((InetSocketAddress) listener.localAddress()).getPort(); }

    @Override public void close() {
        for (Channel channel : clients) channel.close();
        clients.clear();
        if (listener != null) listener.close();
        if (group != null) group.shutdownGracefully();
        listener = null;
        group = null;
    }

    private final class Handler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
        private final Session session = new Session();
        @Override public void userEventTriggered(ChannelHandlerContext ctx, Object event) throws Exception {
            if(event == WebSocketServerProtocolHandler.ServerHandshakeStateEvent.HANDSHAKE_COMPLETE) {
                // Minecraft's Netty 4.0.10 protocol handler has no frame-limit
                // constructor. Upgrade its installed RFC6455 decoder once the
                // handshake completes, before the client sends a work plan.
                var decoder=ctx.pipeline().get(WebSocket08FrameDecoder.class);
                if(decoder!=null)ctx.pipeline().replace(decoder,"modbench-ws-decoder",new WebSocket13FrameDecoder(true,false,8 << 20));
            }
            super.userEventTriggered(ctx,event);
        }
        @Override public void channelActive(ChannelHandlerContext ctx) throws Exception {
            clients.add(ctx.channel());
            super.channelActive(ctx);
        }
        @Override public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            session.disconnect();
            clients.remove(ctx.channel());
            super.channelInactive(ctx);
        }
        @Override protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame frame) {
            JsonElement id = com.google.gson.JsonNull.INSTANCE;
            try {
                JsonObject body = new JsonParser().parse(frame.text()).getAsJsonObject();
                if (body.has("id")) id = body.get("id");
                if (!(id.isJsonPrimitive() || id.isJsonNull())) throw new IllegalArgumentException("id must be scalar");
                if (!session.authenticated) {
                    if (!token.equals(Json.string(body, "auth", ""))) {
                        ctx.writeAndFlush(new TextWebSocketFrame(Json.object("id", id, "ok", false,
                            "error", Json.object("code", "unauthorized", "msg", "authenticate first")).toString()));
                        return;
                    }
                    session.authenticated = true;
                    ctx.writeAndFlush(new TextWebSocketFrame(Json.object("id", id, "ok", true, "data", "ok").toString()));
                    return;
                }
                JsonObject params = body.has("params") ? body.getAsJsonObject("params") : new JsonObject();
                if (params == null) throw new IllegalArgumentException("params must be an object");
                Request request = new Request(id, Json.string(body, "method", ""), params, session, runtime,
                    out -> { if (ctx.channel().isActive()) ctx.writeAndFlush(new TextWebSocketFrame(out.toString())); });
                if (session.pending.size() >= 128) throw new IllegalArgumentException("too many pending requests");
                if (session.pending.putIfAbsent(id.toString(), request) != null) {
                    // A duplicate ID cannot be answered without impersonating the original request.
                    // Close this invalid session; its pending actions will release their controls.
                    ctx.close();
                    return;
                }
                // Fails the request only while it is still queued; a running handler answers with late=true.
                ctx.executor().schedule(request::expire, Math.max(1L, request.deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                runtime.dispatch(request);
            } catch (Exception e) {
                ctx.writeAndFlush(new TextWebSocketFrame(Json.object("id", id, "ok", false,
                    "error", Json.object("code", "bad_request", "msg", e.getMessage())).toString()));
            }
        }
        @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) { ctx.close(); }
    }
}
