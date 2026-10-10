// SPDX-License-Identifier: MIT
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.client;

import com.google.gson.JsonObject;
import dev.modbench.bridge.BridgeRuntime;
import dev.modbench.bridge.Request;
import dev.modbench.bridge.Session;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** A job that ended while held (its step was over, nobody waiting) answers nav.resume as it would have answered a waiter. */
public class HeldJobTest {
    private static JsonObject collect(Map<String,Object> receipt,boolean succeeded) {
        BridgeRuntime runtime=new BridgeRuntime("test") {
            @Override protected void controlsChanged(String reason) {}
            @Override protected void maintainControls() {}
        };
        List<JsonObject> replies=new ArrayList<>();
        Request resume=new Request(new com.google.gson.JsonPrimitive(1),"nav.resume",new JsonObject(),new Session(),runtime,replies::add);
        ClientRuntime.answer(resume,receipt,ClientRuntime.failure(receipt,succeeded));
        assertEquals(1,replies.size());return replies.get(0);
    }
    @Test public void aFailedHeldJobIsAnErrorWithItsReceiptNotAnOkReply() {
        JsonObject reply=collect(Map.of("action","mine","state","failed","reason","no_path","jobId","j-1"),false);
        assertEquals(false,reply.get("ok").getAsBoolean());
        JsonObject error=reply.getAsJsonObject("error");
        assertEquals("path_failed",error.get("code").getAsString());assertEquals("no_path",error.get("msg").getAsString());
        assertEquals("j-1",error.getAsJsonObject("receipt").get("jobId").getAsString());
        assertEquals("build_failed",collect(Map.of("action","build","state","failed","reason","missing_materials"),false).getAsJsonObject("error").get("code").getAsString());
    }
    @Test public void aHeldJobAGuardEndedIsCancelledAndOneThatSucceededOrPausedIsAReply() {
        assertEquals("cancelled",collect(Map.of("action","goto","state","cancelled","reason","world_paused: health_dropped"),false).getAsJsonObject("error").get("code").getAsString());
        assertTrue(collect(Map.of("action","goto","state","succeeded"),true).get("ok").getAsBoolean());
        assertNull("a paused build is resumable, not failed",ClientRuntime.failure(Map.of("action","build","state","paused"),false));
    }
}
