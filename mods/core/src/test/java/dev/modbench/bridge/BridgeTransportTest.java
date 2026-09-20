// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
package dev.modbench.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** The real BridgeTransport on an ephemeral loopback port, driven by a websocket client; the "game thread" is a ticker. */
public class BridgeTransportTest {
    /** Async jobs are held until released, cancelled, expired or disconnected, like the client's interaction jobs. */
    private static final class Runtime extends BridgeRuntime {
        final List<Request> held = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<String> released = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger calls = new AtomicInteger(), sleeps = new AtomicInteger();
        Runtime() {
            super("test");
            register("obs.fast", "fast read", "read", r -> Json.object("fast", true));
            register("act.count", "counted mutation", "interaction", r -> { calls.incrementAndGet(); return Json.object("counted", true); });
            register("act.hold", "async job", "interaction", r -> { held.add(r); return null; });
            register("act.release", "finish held jobs", "interaction", r -> {
                for (Request job : held) job.reply(Json.object("released", true));
                held.clear(); return Json.object("ok", true);
            });
            register("act.sleep", "synchronous handler taking {ms}", "interaction", r -> {
                Thread.sleep(Json.integer(r.params, "ms", 0, 0, 10000)); sleeps.incrementAndGet(); return Json.object("slept", true);
            });
        }
        @Override protected void controlsChanged(String reason) {}
        @Override protected void maintainControls() {
            for (Request r : held) if (r.isDone() || !r.session.connected || r.expired()) {
                released.add(r.id + " done=" + r.isDone() + " connected=" + r.session.connected);
                r.fail("cancelled", "released by maintain"); held.remove(r);
            }
        }
    }

    private static final class Client extends SimpleChannelInboundHandler<Object> {
        final LinkedBlockingQueue<JsonObject> frames = new LinkedBlockingQueue<>();
        final CountDownLatch open = new CountDownLatch(1), closed = new CountDownLatch(1);
        final WebSocketClientHandshaker handshaker;
        Channel channel;
        Client(URI uri) { handshaker = WebSocketClientHandshakerFactory.newHandshaker(uri, WebSocketVersion.V13, null, false, new DefaultHttpHeaders()); }
        @Override public void channelActive(ChannelHandlerContext ctx) { handshaker.handshake(ctx.channel()); }
        @Override public void channelInactive(ChannelHandlerContext ctx) { closed.countDown(); }
        @Override protected void channelRead0(ChannelHandlerContext ctx, Object message) {
            if (!handshaker.isHandshakeComplete()) { handshaker.finishHandshake(ctx.channel(), (FullHttpResponse) message); open.countDown(); }
            else if (message instanceof TextWebSocketFrame text) frames.add(new JsonParser().parse(text.text()).getAsJsonObject());
        }
        @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) { ctx.close(); }
        void send(JsonObject body) { channel.writeAndFlush(new TextWebSocketFrame(body.toString())); }
        void call(int id, String method, Object... params) { send(Json.object("id", id, "method", method, "params", Json.object(params))); }
        JsonObject next() throws InterruptedException {
            JsonObject frame = frames.poll(5, TimeUnit.SECONDS); assertNotNull("no reply within 5 s", frame); return frame;
        }
    }

    private Runtime runtime;
    private BridgeTransport transport;
    private NioEventLoopGroup group;
    private Thread ticker;
    private Path tokenFile;
    private String token, previousTokenFile;
    private volatile boolean running = true, stalled;

    @Before public void start() throws Exception {
        tokenFile = Files.createTempFile("modbench-bridge", ".token");
        previousTokenFile = System.setProperty("modbench.tokenFile", tokenFile.toString());
        runtime = new Runtime();
        transport = new BridgeTransport(runtime);
        transport.start(0);
        token = Files.readString(tokenFile);
        group = new NioEventLoopGroup(1);
        Object world = new Object();
        ticker = new Thread(() -> {
            while (running) {
                if (!stalled) runtime.startTick(world);
                try { Thread.sleep(2); } catch (InterruptedException e) { return; }
            }
        }, "test-game-thread");
        ticker.setDaemon(true); ticker.start();
    }

    @After public void stop() throws Exception {
        running = false; ticker.join(5000);
        transport.close(); group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        if (previousTokenFile == null) System.clearProperty("modbench.tokenFile"); else System.setProperty("modbench.tokenFile", previousTokenFile);
        Files.deleteIfExists(tokenFile);
    }

    private Client connect(boolean authenticate) throws Exception {
        URI uri = new URI("ws://127.0.0.1:" + transport.port() + "/ws");
        Client client = new Client(uri);
        client.channel = new Bootstrap().group(group).channel(NioSocketChannel.class).handler(new ChannelInitializer<SocketChannel>() {
            @Override protected void initChannel(SocketChannel ch) { ch.pipeline().addLast(new HttpClientCodec(), new HttpObjectAggregator(1 << 20), client); }
        }).connect("127.0.0.1", transport.port()).sync().channel();
        assertTrue("websocket handshake", client.open.await(5, TimeUnit.SECONDS));
        if (authenticate) { client.send(Json.object("id", 0, "auth", token)); assertTrue(client.next().get("ok").getAsBoolean()); }
        return client;
    }
    private static String code(JsonObject reply) { return reply.getAsJsonObject("error").get("code").getAsString(); }
    private void awaitHeld(int count) throws InterruptedException {
        long until = System.nanoTime() + 5_000_000_000L;
        while (runtime.held.size() != count && System.nanoTime() < until) Thread.sleep(5);
        assertEquals(count, runtime.held.size());
    }

    @Test public void requestsAreRejectedUntilTheTokenIsPresented() throws Exception {
        Client client = connect(false);
        client.call(1, "act.count");
        assertEquals("unauthorized", code(client.next()));
        client.send(Json.object("id", 2, "auth", "wrong"));
        assertEquals("unauthorized", code(client.next()));
        client.send(Json.object("id", 3, "auth", token));
        assertTrue(client.next().get("ok").getAsBoolean());
        client.call(4, "act.count");
        JsonObject reply = client.next();
        assertEquals(4, reply.get("id").getAsInt());
        assertTrue(reply.getAsJsonObject("data").get("counted").getAsBoolean());
        assertEquals("only the authenticated call executed", 1, runtime.calls.get());
    }

    @Test public void repliesArriveOutOfOrderAndEachIdIsAnsweredOnce() throws Exception {
        Client client = connect(true);
        client.call(1, "act.hold");
        client.call(2, "obs.fast");
        assertEquals("the fast read overtakes the held job", 2, client.next().get("id").getAsInt());
        client.call(3, "act.release");
        List<Integer> ids = new ArrayList<>(List.of(client.next().get("id").getAsInt(), client.next().get("id").getAsInt()));
        Collections.sort(ids);
        assertEquals(List.of(1, 3), ids);
        assertNull("no further frames", client.frames.poll(200, TimeUnit.MILLISECONDS));
    }

    @Test public void duplicatePendingIdClosesTheSessionAndReleasesItsJob() throws Exception {
        Client client = connect(true);
        client.call(7, "act.hold");
        awaitHeld(1);
        client.call(7, "act.count");
        assertTrue("session closed on a duplicate id", client.closed.await(5, TimeUnit.SECONDS));
        awaitHeld(0);
        assertEquals(List.of("7 done=true connected=false"), runtime.released);
        assertEquals("the impersonating request never ran", 0, runtime.calls.get());
    }

    @Test public void theHundredAndTwentyNinthPendingRequestIsRefusedUntilASlotFrees() throws Exception {
        Client client = connect(true);
        client.call(1, "act.hold", "_timeout_ms", 1000);
        for (int id = 2; id <= 128; id++) client.call(id, "act.hold");
        awaitHeld(128);
        client.call(129, "obs.fast");
        JsonObject refused = client.next();
        assertEquals(129, refused.get("id").getAsInt());
        assertEquals("bad_request", code(refused));
        assertEquals("too many pending requests", refused.getAsJsonObject("error").get("msg").getAsString());
        JsonObject expired = client.next();
        assertEquals(1, expired.get("id").getAsInt());
        assertEquals("timeout", code(expired));
        client.call(130, "obs.fast");
        assertTrue("a freed slot admits new work", client.next().get("ok").getAsBoolean());
    }

    @Test public void aFullSessionCanStillCancelItsOwnWork() throws Exception {
        Client client = connect(true);
        for (int id = 1; id <= 128; id++) client.call(id, "act.hold");
        awaitHeld(128);
        client.call(129, "requests.cancel", "requestId", 1);
        JsonObject first = client.next(), second = client.next();
        JsonObject ack = first.get("id").getAsInt() == 129 ? first : second, cancelled = ack == first ? second : first;
        assertTrue(ack.getAsJsonObject("data").get("cancelled").getAsBoolean());
        assertEquals("cancelled", code(cancelled));
        awaitHeld(127);
    }

    @Test public void handlerRunningAcrossTheDeadlineAnswersOnceWithItsRealOutcomeMarkedLate() throws Exception {
        Client client = connect(true);
        client.call(14, "act.sleep", "ms", 400, "_timeout_ms", 100);
        JsonObject reply = client.next();
        assertEquals(14, reply.get("id").getAsInt());
        assertTrue("the action happened, so the caller must not be told it timed out", reply.get("ok").getAsBoolean());
        assertTrue(reply.getAsJsonObject("data").get("slept").getAsBoolean());
        assertTrue(reply.get("late").getAsBoolean());
        assertTrue(reply.get("cost_ms").getAsDouble() >= 400);
        assertEquals(1, runtime.sleeps.get());
        assertNull("exactly one reply", client.frames.poll(300, TimeUnit.MILLISECONDS));
    }

    @Test public void deadlineTimerFailsAQueuedRequestWhichThenNeverExecutes() throws Exception {
        Client client = connect(true);
        stalled = true; Thread.sleep(20);
        client.call(15, "act.count", "_timeout_ms", 100);
        JsonObject reply = client.next();
        assertEquals("timeout", code(reply));
        assertFalse(reply.has("late"));
        stalled = false;
        client.call(16, "obs.fast");
        assertEquals(16, client.next().get("id").getAsInt());
        assertEquals("the timed-out mutation was skipped by the game thread", 0, runtime.calls.get());
        assertNull(client.frames.poll(200, TimeUnit.MILLISECONDS));
    }

    @Test public void asyncJobOutlivingItsDeadlineGetsTimeoutThenMaintainReleasesIt() throws Exception {
        Client client = connect(true);
        client.call(13, "act.hold", "_timeout_ms", 100);
        JsonObject reply = client.next();
        assertEquals(13, reply.get("id").getAsInt());
        assertEquals("timeout", code(reply));
        assertFalse(reply.has("late"));
        awaitHeld(0);
        assertEquals(List.of("13 done=true connected=true"), runtime.released);
        assertNull("exactly one reply", client.frames.poll(300, TimeUnit.MILLISECONDS));
    }

    @Test public void cancelAnswersBothRequestsAndIsScopedToTheSession() throws Exception {
        Client owner = connect(true), other = connect(true);
        owner.call(10, "act.hold");
        awaitHeld(1);
        other.call(1, "requests.cancel", "requestId", 10);
        assertFalse(other.next().getAsJsonObject("data").get("cancelled").getAsBoolean());
        owner.call(11, "requests.cancel", "requestId", 10);
        JsonObject first = owner.next(), second = owner.next();
        JsonObject cancelled = first.get("id").getAsInt() == 10 ? first : second, ack = cancelled == first ? second : first;
        assertEquals("cancelled", code(cancelled));
        assertTrue(ack.getAsJsonObject("data").get("cancelled").getAsBoolean());
        awaitHeld(0);
        assertEquals(List.of("10 done=true connected=true"), runtime.released);
        assertNull(owner.frames.poll(200, TimeUnit.MILLISECONDS));
    }

    @Test public void disconnectFailsQueuedAndRunningRequestsExactlyOnceWithoutExecutingThem() throws Exception {
        Client client = connect(true);
        client.call(18, "act.hold");
        awaitHeld(1);
        stalled = true; Thread.sleep(20);
        client.call(19, "act.count");
        client.call(20, "act.count");
        Thread.sleep(100);
        client.channel.close().sync();
        Thread.sleep(100);
        stalled = false;
        awaitHeld(0);
        assertEquals(List.of("18 done=true connected=false"), runtime.released);
        assertEquals("queued mutations of a closed session never run", 0, runtime.calls.get());
        Client next = connect(true);
        next.call(1, "act.count");
        assertTrue("the bridge keeps serving new sessions", next.next().get("ok").getAsBoolean());
        assertEquals(1, runtime.calls.get());
    }
}
