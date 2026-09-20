// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
package dev.modbench.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.util.Arrays;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagByte;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagInt;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class NbtSnapshotsTest {
    private static final String OWNER = "player-a";
    private static final String WORLD = "DIM0";

    @Test
    public void remembersUniqueHandlesAndFrozenTagCopies() throws Exception {
        NbtSnapshots snapshots = new NbtSnapshots();
        NBTTagCompound original = new NBTTagCompound();
        original.setInteger("energy", 7);

        JsonObject sourceProvenance=provenance();
        String first = snapshots.remember(original, OWNER, WORLD, sourceProvenance);
        sourceProvenance.addProperty("kind","changed");
        String second = snapshots.remember(original, OWNER, WORLD, provenance());
        assertNotEquals(first, second);

        original.setInteger("energy", 99);
        JsonObject read = snapshots.read(request(first, "energy"), OWNER, WORLD);
        assertEquals("int", read.get("type").getAsString());
        assertEquals(7, read.get("value").getAsInt());
        assertTrue(read.get("snapshot").getAsBoolean());
        assertEquals("tile", read.getAsJsonObject("provenance").get("kind").getAsString());
    }

    @Test
    public void guardsHandlesByOwnerAndWorld() throws Exception {
        NbtSnapshots snapshots = new NbtSnapshots();
        String handle = snapshots.remember(simple(), OWNER, WORLD, provenance());
        assertIllegal(() -> snapshots.read(request(handle, ""), "player-b", WORLD));
        assertIllegal(() -> snapshots.read(request(handle, ""), OWNER, "DIM-1"));
    }

    @Test
    public void resolvesExactKeyArrayPathsAndRejectsMissingOrBadIndices() {
        NBTTagCompound root = new NBTTagCompound();
        root.setString("key.with/slash", "exact");
        NBTTagList list = new NBTTagList();
        list.appendTag(new NBTTagInt(4));
        root.setTag("entries", list);

        JsonArray exact = new JsonArray();
        exact.add(new JsonPrimitive("key.with/slash"));
        assertEquals("\"exact\"", NbtSnapshots.resolve(root, exact).toString());
        assertEquals(4, ((NBTBase.NBTPrimitive) NbtSnapshots.resolve(root, path("entries", "0"))).func_150287_d());
        assertIllegal(() -> NbtSnapshots.resolve(root, path("missing")));
        assertIllegal(() -> NbtSnapshots.resolve(root, path("entries", "1")));
        assertIllegal(() -> NbtSnapshots.resolve(root, path("entries", "not-an-index")));
    }

    @Test
    public void describesNestedNumericTagsWithoutChangingSource() throws Exception {
        NbtSnapshots snapshots = new NbtSnapshots();
        NBTTagCompound nested = new NBTTagCompound();
        nested.setByte("byte", (byte) -2);
        nested.setInteger("int", 123456);
        nested.setLong("long", 900719925474099L);
        NBTTagCompound root = new NBTTagCompound();
        root.setTag("nested", nested);
        String handle = snapshots.remember(root, OWNER, WORLD, provenance());

        assertNumeric(snapshots.read(request(handle, path("nested", "byte")), OWNER, WORLD), "byte", -2L);
        assertNumeric(snapshots.read(request(handle, path("nested", "int")), OWNER, WORLD), "int", 123456L);
        assertNumeric(snapshots.read(request(handle, path("nested", "long")), OWNER, WORLD), "long", 900719925474099L);
        assertEquals(-2, nested.getByte("byte"));
        assertEquals(123456, nested.getInteger("int"));
        assertEquals(900719925474099L, nested.getLong("long"));
    }

    @Test
    public void paginatesArraysAndListsWithoutMutatingOriginals() throws Exception {
        NbtSnapshots snapshots = new NbtSnapshots();
        byte[] bytes = new byte[] { 10, 11, 12, 13 };
        NBTTagList list = new NBTTagList();
        list.appendTag(new NBTTagInt(20));
        list.appendTag(new NBTTagInt(21));
        list.appendTag(new NBTTagInt(22));
        NBTTagCompound root = new NBTTagCompound();
        root.setByteArray("bytes", bytes);
        root.setTag("list", list);
        String handle = snapshots.remember(root, OWNER, WORLD, provenance());

        JsonObject bytePage = snapshots.read(request(handle, "bytes", 1, 2), OWNER, WORLD);
        assertEquals("byte[]", bytePage.get("type").getAsString());
        assertEquals(4, bytePage.get("size").getAsInt());
        assertEquals(1, bytePage.get("offset").getAsInt());
        assertEquals(2, bytePage.get("limit").getAsInt());
        assertEquals(11, bytePage.getAsJsonArray("value").get(0).getAsInt());
        assertEquals(12, bytePage.getAsJsonArray("value").get(1).getAsInt());
        assertTrue(bytePage.get("truncated").getAsBoolean());

        JsonObject listPage = snapshots.read(request(handle, "list", 1, 1), OWNER, WORLD);
        assertEquals(21, listPage.getAsJsonArray("value").get(0).getAsInt());
        assertArrayEquals(new byte[] { 10, 11, 12, 13 }, root.getByteArray("bytes"));
        assertEquals(3, list.tagCount());
        assertEquals(20, ((NBTBase.NBTPrimitive) NbtSnapshots.element(list, 0)).func_150287_d());
    }

    @Test
    public void depthAndBudgetBoundLongUnicodeWithoutClaimingFullData() {
        NBTTagCompound root = new NBTTagCompound();
        NBTTagCompound child = new NBTTagCompound();
        child.setString("text", repeated("\u706b\uD83D\uDE80", 600));
        root.setTag("child", child);

        JsonObject depthLimited = NbtSnapshots.describe(root, describe(0, 4096));
        assertTrue(depthLimited.get("truncated").getAsBoolean());
        assertTrue(depthLimited.getAsJsonObject("value").get("_elided").getAsBoolean());

        JsonObject budgetLimited = NbtSnapshots.describe(child.getTag("text"), describe(8, 256));
        assertTrue(budgetLimited.get("truncated").getAsBoolean());
        assertTrue(budgetLimited.getAsJsonObject("value").get("_elided").getAsBoolean());
        assertEquals(repeated("\u706b\uD83D\uDE80", 600), child.getString("text"));
    }

    @Test
    public void evictsOldestAtSixtyFourHandlesAndRejectsOversizeTags() throws Exception {
        NbtSnapshots snapshots = new NbtSnapshots();
        String first = null;
        String last = null;
        for (int i = 0; i < 65; i++) {
            String handle = snapshots.remember(simple(), OWNER, WORLD, provenance());
            if (i == 0) first = handle;
            last = handle;
        }
        final String evicted = first;
        final String retained = last;
        assertIllegal(() -> snapshots.read(request(evicted, ""), OWNER, WORLD));
        assertEquals(1, snapshots.read(request(retained, "value"), OWNER, WORLD).get("value").getAsInt());

        NBTTagCompound tooLarge = new NBTTagCompound();
        for (int i = 0; i < 20; i++) tooLarge.setString("payload" + i, repeated("x", 60_000));
        try {
            snapshots.remember(tooLarge, OWNER, WORLD, provenance());
            fail("expected max tag size rejection");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("exceeds"));
        }
    }

    private static void assertNumeric(JsonObject value, String type, long expected) {
        assertEquals(type, value.get("type").getAsString());
        assertEquals(expected, value.get("value").getAsLong());
    }

    private static NBTTagCompound simple() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("value", 1);
        return tag;
    }

    private static JsonObject provenance() {
        JsonObject provenance = new JsonObject();
        provenance.addProperty("kind", "tile");
        return provenance;
    }

    private static JsonObject request(String handle, String path) {
        JsonObject request = new JsonObject();
        request.addProperty("handle", handle);
        request.addProperty("path", path);
        return request;
    }

    private static JsonObject request(String handle, JsonArray path) {
        JsonObject request = new JsonObject();
        request.addProperty("handle", handle);
        request.add("path", path);
        return request;
    }

    private static JsonObject request(String handle, String path, int offset, int limit) {
        JsonObject request = request(handle, path);
        request.addProperty("offset", offset);
        request.addProperty("limit", limit);
        return request;
    }

    private static JsonArray path(String... parts) {
        JsonArray path = new JsonArray();
        for (String part : parts) path.add(new JsonPrimitive(part));
        return path;
    }

    private static JsonObject describe(int depth, int budget) {
        JsonObject params = new JsonObject();
        params.addProperty("depth", depth);
        params.addProperty("budget", budget);
        return params;
    }

    private static String repeated(String text, int count) {
        StringBuilder out = new StringBuilder(text.length() * count);
        for (int i = 0; i < count; i++) out.append(text);
        return out.toString();
    }

    private static void assertIllegal(Runnable operation) {
        try {
            operation.run();
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}





