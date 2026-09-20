// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** Contract tests for the game-thread queue and request ownership boundaries. */
public class BridgeRuntimeTest {
    private static final class Runtime extends BridgeRuntime {
        final List<String> controls = new ArrayList<>();
        int maintained;

        Runtime() { super("test"); }
        void add(String name, Handler handler) { register(name, name, "interaction", handler); }
        void addRead(String name, Handler handler) { register(name, name, "read", handler); }
        @Override protected void controlsChanged(String reason) { controls.add(reason); }
        @Override protected void maintainControls() { maintained++; }
    }

    private static Request request(Runtime runtime, Session session, int id, String method,
                                   JsonObject params, List<JsonObject> replies) {
        Request request = new Request(new JsonPrimitive(id), method, params, session, runtime, replies::add);
        session.pending.put(request.id.toString(), request);
        return request;
    }

    @Test
    public void batchRejectsUnapprovedReadsAndMutationsWithoutCallingThem() {
        Runtime runtime=new Runtime();AtomicInteger calls=new AtomicInteger();
        runtime.addRead("obs.safe",r->Json.object("value",7));runtime.watchable("obs.safe");
        runtime.addRead("obs.async",r->{calls.incrementAndGet();return null;});
        runtime.watchableRemote("obs.async");
        runtime.add("act.use",r->{calls.incrementAndGet();return "bad";});
        var params=Json.object("queries",Json.object("good",Json.object("method","obs.safe"),"async",Json.object("method","obs.async"),"write",Json.object("method","act.use")));
        var result=runtime.observeBatch(request(runtime,new Session(),1,"obs.batch",params,new ArrayList<>()),Json.object("bridgeId",runtime.bridgeId()));
        assertEquals(7,result.getAsJsonObject("values").getAsJsonObject("good").get("value").getAsInt());
        assertEquals(2,result.getAsJsonObject("errors").entrySet().size());assertEquals(0,calls.get());
        assertTrue(java.util.stream.StreamSupport.stream(runtime.describe().spliterator(),false)
            .map(e->e.getAsJsonObject()).filter(e->e.get("name").getAsString().equals("obs.async"))
            .allMatch(e->e.get("watchable").getAsBoolean()&&e.get("thread").getAsString().equals("server_observation")));
        assertFalse(runtime.bridgeId().equals(new Runtime().bridgeId()));
    }

    @Test
    public void urgentInterruptCanBarrierQueuedActionsWithoutSimulationTicks() {
        Runtime runtime=new Runtime();AtomicInteger calls=new AtomicInteger();Object world=new Object();runtime.service(world);
        runtime.add("act.work",r->{calls.incrementAndGet();return "work";});
        runtime.add("interrupt.fire",r->{runtime.cancelQueuedInteractions();return "fired";});
        Session session=new Session();List<JsonObject> replies=new ArrayList<>();
        runtime.dispatch(request(runtime,session,1,"act.work",new JsonObject(),replies));
        runtime.dispatch(request(runtime,session,2,"interrupt.fire",new JsonObject(),new ArrayList<>()));runtime.service(world);
        assertEquals(0,calls.get());assertEquals(0,runtime.tick());assertEquals("cancelled",replies.get(0).getAsJsonObject("error").get("code").getAsString());
    }

    @Test
    public void longNavigationDeadlineRemainsCancellable() {
        Runtime runtime=new Runtime();Session session=new Session();List<JsonObject> replies=new ArrayList<>();
        JsonObject params=new JsonObject();params.addProperty("_timeout_ms",900000);
        Request r=request(runtime,session,1,"baritone.goto",params,replies);
        assertTrue(r.deadline-System.nanoTime()>899_000_000_000L);
        r.fail("cancelled","stop long route");
        assertTrue(r.isDone());assertTrue(session.pending.isEmpty());assertEquals(1,replies.size());
    }

    @Test
    public void elapsedDeadlineFailsBeforeHandlerCanMutate() throws Exception {
        Runtime runtime = new Runtime();
        AtomicInteger mutations = new AtomicInteger();
        runtime.add("act.mutate", request -> { mutations.incrementAndGet(); return "mutated"; });
        Object world = new Object();
        runtime.startTick(world);
        Session session = new Session();
        List<JsonObject> replies = new ArrayList<>();
        JsonObject params = new JsonObject();
        params.addProperty("_timeout_ms", 1);

        runtime.dispatch(request(runtime, session, 1, "act.mutate", params, replies));
        Thread.sleep(10);
        runtime.startTick(world);

        assertEquals(0, mutations.get());
        assertEquals(1, replies.size());
        assertEquals("timeout", replies.get(0).getAsJsonObject("error").get("code").getAsString());
        assertTrue(session.pending.isEmpty());
    }

    @Test
    public void timerExpiryOfQueuedRequestAnswersTimeoutAndSkipsExecution() {
        Runtime runtime = new Runtime();
        AtomicInteger mutations = new AtomicInteger();
        runtime.add("act.mutate", request -> { mutations.incrementAndGet(); return "mutated"; });
        Object world = new Object();
        runtime.startTick(world);
        Session session = new Session();
        List<JsonObject> replies = new ArrayList<>();
        Request request = request(runtime, session, 1, "act.mutate", new JsonObject(), replies);
        runtime.dispatch(request);

        request.expire(); // the transport's deadline timer, firing before the game thread drains
        runtime.startTick(world);

        assertEquals(0, mutations.get());
        assertEquals(1, replies.size());
        assertEquals("timeout", replies.get(0).getAsJsonObject("error").get("code").getAsString());
        assertFalse(replies.get(0).has("late"));
        assertTrue(session.pending.isEmpty());
    }

    @Test
    public void handlerCrossingItsDeadlineAnswersOnceWithTheTrueOutcomeMarkedLate() {
        Runtime runtime = new Runtime();
        AtomicInteger mutations = new AtomicInteger();
        runtime.add("act.mutate", request -> {
            mutations.incrementAndGet();
            request.expire(); // timer fires while the handler runs: must not answer
            while (!request.expired()) Thread.onSpinWait();
            request.expire(); // and again after the deadline, still while running
            return "mutated";
        });
        Object world = new Object();
        runtime.startTick(world);
        Session session = new Session();
        List<JsonObject> replies = new ArrayList<>();
        JsonObject params = new JsonObject();
        params.addProperty("_timeout_ms", 1);
        Request request = request(runtime, session, 2, "act.mutate", params, replies);
        runtime.dispatch(request);

        runtime.startTick(world);
        request.expire(); // a timer firing after completion is a no-op too

        assertEquals(1, mutations.get());
        assertEquals(1, replies.size());
        assertTrue(replies.get(0).get("ok").getAsBoolean());
        assertEquals("mutated", replies.get(0).get("data").getAsString());
        assertTrue(replies.get(0).get("late").getAsBoolean());
        assertTrue(session.pending.isEmpty());
    }

    @Test
    public void asyncJobIsHandedBackToTheDeadlineTimerOnceItsHandlerReturns() {
        Runtime runtime = new Runtime();
        runtime.add("act.job", request -> { request.expire(); return null; }); // timer during the handler: ignored
        Object world = new Object();
        runtime.startTick(world);
        Session session = new Session();
        List<JsonObject> replies = new ArrayList<>();
        Request request = request(runtime, session, 4, "act.job", new JsonObject(), replies);
        runtime.dispatch(request);
        runtime.startTick(world);
        assertFalse(request.isDone());

        request.expire(); // the job is async now, so the timer answers as it always did
        request.reply("job finished after the caller was told");

        assertEquals(1, replies.size());
        assertEquals("timeout", replies.get(0).getAsJsonObject("error").get("code").getAsString());
        assertFalse(replies.get(0).has("late"));
    }

    @Test
    public void replyInsideTheDeadlineCarriesNoLateMarker() {
        Runtime runtime = new Runtime();
        runtime.add("act.quick", request -> "done");
        Object world = new Object();
        runtime.startTick(world);
        Session session = new Session();
        List<JsonObject> replies = new ArrayList<>();
        runtime.dispatch(request(runtime, session, 3, "act.quick", new JsonObject(), replies));
        runtime.startTick(world);
        assertEquals(1, replies.size());
        assertFalse(replies.get(0).has("late"));
    }

    @Test
    public void cancellationIsScopedToItsOwningSession() {
        Runtime runtime = new Runtime();
        AtomicInteger calls = new AtomicInteger();
        runtime.add("act.work", request -> { calls.incrementAndGet(); return "done"; });
        Object world = new Object();
        runtime.startTick(world);
        Session owner = new Session();
        Session other = new Session();
        List<JsonObject> ownerReplies = new ArrayList<>();
        List<JsonObject> otherReplies = new ArrayList<>();
        Request target = request(runtime, owner, 5, "act.work", new JsonObject(), ownerReplies);
        runtime.dispatch(target);
        JsonObject cancel = new JsonObject();
        cancel.addProperty("requestId", 5);
        runtime.dispatch(request(runtime, other, 6, "requests.cancel", cancel, otherReplies));

        assertFalse(target.isDone());
        assertFalse(otherReplies.get(0).getAsJsonObject("data").get("cancelled").getAsBoolean());
        runtime.startTick(world);
        assertEquals(1, calls.get());
        assertEquals(1, ownerReplies.size());
    }

    @Test
    public void disconnectFailsQueuedRequestOnceWithoutExecutingIt() {
        Runtime runtime = new Runtime();
        AtomicInteger calls = new AtomicInteger();
        runtime.add("act.work", request -> { calls.incrementAndGet(); return "done"; });
        Session session = new Session();
        List<JsonObject> replies = new ArrayList<>();
        runtime.dispatch(request(runtime, session, 7, "act.work", new JsonObject(), replies));

        session.disconnect();
        runtime.startTick(new Object());

        assertEquals(0, calls.get());
        assertEquals(1, replies.size());
        assertEquals("disconnected", replies.get(0).getAsJsonObject("error").get("code").getAsString());
    }

    @Test
    public void stopCancelsQueuedInteractionsButKeepsObservations() {
        Runtime runtime = new Runtime();
        List<String> order = new ArrayList<>();
        runtime.add("act.work", request -> { order.add("work"); return "done"; });
        runtime.add("act.stop", request -> { order.add("stop"); return "stopped"; });
        runtime.addRead("obs.state", request -> { order.add("observe"); return "observed"; });
        Object world = new Object();
        runtime.startTick(world);
        Session session = new Session();
        List<JsonObject> workReplies = new ArrayList<>();
        runtime.dispatch(request(runtime, session, 8, "act.work", new JsonObject(), workReplies));
        runtime.dispatch(request(runtime, session, 9, "act.stop", new JsonObject(), new ArrayList<>()));
        runtime.dispatch(request(runtime, session, 10, "obs.state", new JsonObject(), new ArrayList<>()));

        runtime.startTick(world);

        assertEquals(java.util.Arrays.asList("stop", "observe"), order);
        assertEquals("cancelled", workReplies.get(0).getAsJsonObject("error").get("code").getAsString());
    }

    @Test
    public void worldChangeClearsQueuedActionsBeforeTheyRun() {
        Runtime runtime = new Runtime();
        AtomicInteger calls = new AtomicInteger();
        runtime.add("act.work", request -> { calls.incrementAndGet(); return "done"; });
        Object firstWorld = new Object();
        runtime.startTick(firstWorld);
        Session session = new Session();
        List<JsonObject> replies = new ArrayList<>();
        runtime.dispatch(request(runtime, session, 10, "act.work", new JsonObject(), replies));

        runtime.startTick(new Object());

        assertEquals(0, calls.get());
        assertEquals("world_changed", replies.get(0).getAsJsonObject("error").get("code").getAsString());
        assertTrue(runtime.controls.contains("world_changed"));
    }

    @Test
    public void requestFinishesOnlyOnceAcrossCompetingTerminalPaths() {
        Runtime runtime = new Runtime();
        Session session = new Session();
        List<JsonObject> replies = new ArrayList<>();
        Request request = request(runtime, session, 11, "act.work", new JsonObject(), replies);

        request.fail("cancelled", "cancelled");
        request.reply("late success");
        session.disconnect();

        assertEquals(1, replies.size());
        assertEquals("cancelled", replies.get(0).getAsJsonObject("error").get("code").getAsString());
    }
}
