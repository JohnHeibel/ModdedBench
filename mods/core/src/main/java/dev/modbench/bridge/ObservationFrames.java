// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.bridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Bounded transport framing for authoritative observation replies.
 *
 * <p>The payload is split before Base64 encoding, so a multi-byte UTF-8 code point
 * can cross frames safely. A {@link Decoder} only decodes after all raw bytes have
 * been reassembled in order.</p>
 */
public final class ObservationFrames {
    public static final int MAX_RESULT_BYTES = 1024 * 1024;
    public static final int MAX_CHUNK_BYTES = 12_000;
    public static final int MAX_FRAMES = 88;

    private ObservationFrames() {
    }

    public static List<JsonObject> encode(String id, JsonObject result) {
        requireId(id);
        if (result == null) {
            throw new IllegalArgumentException("result is required");
        }
        byte[] bytes = Json.GSON.toJson(result).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_RESULT_BYTES) {
            throw new IllegalArgumentException("observation result exceeds " + MAX_RESULT_BYTES + " bytes");
        }
        int count = (bytes.length + MAX_CHUNK_BYTES - 1) / MAX_CHUNK_BYTES;
        if (count < 1 || count > MAX_FRAMES) {
            throw new IllegalArgumentException("observation result requires an invalid frame count");
        }

        List<JsonObject> frames = new ArrayList<JsonObject>(count);
        for (int index = 0; index < count; index++) {
            int offset = index * MAX_CHUNK_BYTES;
            int length = Math.min(MAX_CHUNK_BYTES, bytes.length - offset);
            byte[] chunk = new byte[length];
            System.arraycopy(bytes, offset, chunk, 0, length);
            JsonObject frame = new JsonObject();
            frame.addProperty("type", "observation_reply");
            frame.addProperty("id", id);
            frame.addProperty("index", index);
            frame.addProperty("count", count);
            frame.addProperty("data", Base64.getEncoder().encodeToString(chunk));
            frames.add(frame);
        }
        return frames;
    }

    public static final class Decoder {
        private String id;
        private int count = -1;
        private int nextIndex;
        private int totalBytes;
        private boolean complete;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        /**
         * Accepts exactly one next frame. Returns the decoded JSON object only when
         * the final frame has been accepted.
         */
        public JsonObject accept(JsonObject frame) {
            if (complete) {
                throw new IllegalArgumentException("observation reply is already complete");
            }
            if (frame == null) {
                throw new IllegalArgumentException("frame is required");
            }
            if (!"observation_reply".equals(requiredString(frame, "type"))) {
                throw new IllegalArgumentException("unexpected observation frame type");
            }
            String frameId = requiredString(frame, "id");
            requireId(frameId);
            int frameCount = requiredInteger(frame, "count", 1, MAX_FRAMES);
            int index = requiredInteger(frame, "index", 0, frameCount - 1);
            if (count == -1) {
                count = frameCount;
                id = frameId;
            } else if (count != frameCount || !id.equals(frameId)) {
                throw new IllegalArgumentException("inconsistent observation reply identity or count");
            }
            if (index != nextIndex) {
                throw new IllegalArgumentException("observation frames must be received in order");
            }

            byte[] chunk;
            try {
                chunk = Base64.getDecoder().decode(requiredString(frame, "data"));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("invalid observation frame Base64", e);
            }
            if (chunk.length == 0 || chunk.length > MAX_CHUNK_BYTES) {
                throw new IllegalArgumentException("invalid observation frame byte length");
            }
            if (chunk.length > MAX_RESULT_BYTES - totalBytes) {
                throw new IllegalArgumentException("observation reply exceeds " + MAX_RESULT_BYTES + " bytes");
            }
            bytes.write(chunk, 0, chunk.length);
            totalBytes += chunk.length;
            nextIndex++;
            if (nextIndex < count) {
                return null;
            }
            JsonObject result = decodeObject(bytes.toByteArray());
            complete = true;
            return result;
        }
    }

    private static JsonObject decodeObject(byte[] bytes) {
        final String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("observation reply is not valid UTF-8", e);
        }
        final JsonElement parsed;
        try {
            parsed = new JsonParser().parse(text);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("observation reply is not valid JSON", e);
        }
        if (parsed == null || !parsed.isJsonObject()) {
            throw new IllegalArgumentException("observation reply must be a JSON object");
        }
        return parsed.getAsJsonObject();
    }

    private static String requiredString(JsonObject frame, String name) {
        JsonElement value = frame.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(name + " must be a string");
        }
        return value.getAsString();
    }

    private static int requiredInteger(JsonObject frame, String name, int min, int max) {
        JsonElement value = frame.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        double number = value.getAsDouble();
        if (!Double.isFinite(number) || number != Math.rint(number) || number < min || number > max) {
            throw new IllegalArgumentException(name + " must be in " + min + ".." + max);
        }
        return (int) number;
    }

    private static void requireId(String id) {
        if (id == null || id.length() == 0) {
            throw new IllegalArgumentException("id must be nonempty");
        }
    }
}
