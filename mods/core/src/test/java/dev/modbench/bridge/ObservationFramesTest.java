// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ObservationFramesTest {
    @Test
    public void roundTripsUnicodeAcrossRawByteChunks() {
        JsonObject source = new JsonObject();
        source.addProperty("text", repeated("\u706b\uD83D\uDE80", 5_000));
        source.addProperty("label", "caf\u00e9");

        List<JsonObject> frames = ObservationFrames.encode("read-7", source);
        assertTrue(frames.size() > 1);
        ObservationFrames.Decoder decoder = new ObservationFrames.Decoder();
        for (int index = 0; index < frames.size(); index++) {
            byte[] chunk = Base64.getDecoder().decode(frames.get(index).get("data").getAsString());
            assertTrue(chunk.length <= ObservationFrames.MAX_CHUNK_BYTES);
            JsonObject decoded = decoder.accept(frames.get(index));
            if (index + 1 == frames.size()) {
                assertEquals(source, decoded);
            } else {
                assertNull(decoded);
            }
        }
    }

    @Test
    public void rejectsReorderedAndDuplicateFrames() {
        List<JsonObject> frames = ObservationFrames.encode("read-8", largeResult());
        assertTrue(frames.size() > 1);
        assertIllegal(() -> new ObservationFrames.Decoder().accept(frames.get(1)));

        ObservationFrames.Decoder decoder = new ObservationFrames.Decoder();
        assertNull(decoder.accept(frames.get(0)));
        assertIllegal(() -> decoder.accept(frames.get(0)));
    }

    @Test
    public void rejectsInconsistentIdentityAndCount() {
        List<JsonObject> frames = ObservationFrames.encode("read-9", largeResult());
        ObservationFrames.Decoder decoder = new ObservationFrames.Decoder();
        assertNull(decoder.accept(frames.get(0)));

        JsonObject wrongCount = copy(frames.get(1));
        wrongCount.addProperty("count", frames.size() + 1);
        assertIllegal(() -> decoder.accept(wrongCount));

        JsonObject wrongId = copy(frames.get(1));
        wrongId.addProperty("id", "other");
        assertIllegal(() -> decoder.accept(wrongId));
    }

    @Test
    public void rejectsMalformedFramesAndNonObjectPayloads() {
        JsonObject malformed = frame("id", 0, 1, "%%%not-base64%%%");
        assertIllegal(() -> new ObservationFrames.Decoder().accept(malformed));

        JsonObject arrayPayload = frame("id", 0, 1,
                Base64.getEncoder().encodeToString("[]".getBytes(StandardCharsets.UTF_8)));
        assertIllegal(() -> new ObservationFrames.Decoder().accept(arrayPayload));

        JsonObject badIndex = frame("id", 0, 1,
                Base64.getEncoder().encodeToString("{}".getBytes(StandardCharsets.UTF_8)));
        badIndex.addProperty("index", 0.5d);
        assertIllegal(() -> new ObservationFrames.Decoder().accept(badIndex));
    }

    @Test
    public void rejectsOversizeEncodingAndReassembly() {
        JsonObject tooLarge = new JsonObject();
        tooLarge.addProperty("payload", repeated("a", ObservationFrames.MAX_RESULT_BYTES));
        assertIllegal(() -> ObservationFrames.encode("large", tooLarge));

        ObservationFrames.Decoder decoder = new ObservationFrames.Decoder();
        String fullChunk = Base64.getEncoder().encodeToString(new byte[ObservationFrames.MAX_CHUNK_BYTES]);
        for (int index = 0; index < ObservationFrames.MAX_FRAMES; index++) {
            JsonObject frame = frame("bounded", index, ObservationFrames.MAX_FRAMES, fullChunk);
            if (index + 1 == ObservationFrames.MAX_FRAMES) {
                assertIllegal(() -> decoder.accept(frame));
            } else {
                assertNull(decoder.accept(frame));
            }
        }
    }

    @Test
    public void rejectsFurtherFramesAfterCompletion() {
        JsonObject source = new JsonObject();
        source.addProperty("value", 1);
        JsonObject frame = ObservationFrames.encode("once", source).get(0);
        ObservationFrames.Decoder decoder = new ObservationFrames.Decoder();
        assertEquals(source, decoder.accept(frame));
        assertIllegal(() -> decoder.accept(frame));
    }

    private static JsonObject largeResult() {
        JsonObject out = new JsonObject();
        out.addProperty("payload", repeated("x", ObservationFrames.MAX_CHUNK_BYTES * 2));
        return out;
    }

    private static JsonObject frame(String id, int index, int count, String data) {
        JsonObject frame = new JsonObject();
        frame.addProperty("type", "observation_reply");
        frame.addProperty("id", id);
        frame.addProperty("index", index);
        frame.addProperty("count", count);
        frame.addProperty("data", data);
        return frame;
    }

    private static JsonObject copy(JsonObject source) {
        return new JsonParser().parse(Json.GSON.toJson(source)).getAsJsonObject();
    }

    private static String repeated(String text, int count) {
        StringBuilder out = new StringBuilder(text.length() * count);
        for (int i = 0; i < count; i++) out.append(text);
        return out.toString();
    }

    private static void assertIllegal(Runnable action) {
        try {
            action.run();
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
