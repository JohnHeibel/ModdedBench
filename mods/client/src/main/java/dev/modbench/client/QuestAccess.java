// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.client;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Read/query and normal-action bridge for Better Questing 3.7.15-GTNH.
 *
 * This class deliberately has no Better Questing imports: its jar is supplied by the running pack, not the
 * development classpath. It only sends Better Questing's normal client packets; it never invokes quest/task
 * completion, reward claim, choice setter, or NBT mutation methods directly.
 */
public final class QuestAccess {
    private static final String QUESTING_API = "betterquesting.api.api.QuestingAPI";
    private static final String API_REFERENCE = "betterquesting.api.api.ApiReference";
    private static final String NATIVE_PROPS = "betterquesting.api.properties.NativeProps";
    private static final String TRANSLATION = "betterquesting.api2.utils.QuestTranslation";
    private static final String ACTIONS = "betterquesting.network.handlers.NetQuestAction";
    private static final String QUEST_SYNC = "betterquesting.network.handlers.NetQuestSync";
    private static final String CHAPTER_SYNC = "betterquesting.network.handlers.NetChapterSync";
    private static final String CHOICE_REWARD = "bq_standard.rewards.RewardChoice";
    private static final String CHOICE_ACTION = "bq_standard.network.handlers.NetRewardChoice";
    private static final String CHECKBOX_TASK = "bq_standard.tasks.TaskCheckbox";
    private static final String CHECKBOX_ACTION = "bq_standard.network.handlers.NetTaskCheckbox";

    public Map<String, Object> status() {
        try {
            Map<?, ?> quests = questDatabase();
            Map<?, ?> lines = lineDatabase();
            return map("available", true, "implementation", "BetterQuesting-3.7 reflective", "quests", quests.size(),
                    "lines", lines.size(), "actions", List.of("sync", "detect", "checkbox", "selectChoice", "claim"), "serverAcknowledged", false);
        } catch (ReflectiveOperationException | LinkageError ex) {
            return map("available", false, "reason", ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    /** Search a stable ID/title/description projection, with bounded zero-based pagination. */
    public Map<String, Object> search(EntityPlayer player, String query, int offset, int limit) {
        return page(summaries(player, query), offset, limit, "quests", query);
    }

    /** Search quest lines in BQ's native book order, including state totals and stable line UUIDs. */
    public Map<String, Object> lines(EntityPlayer player, String query, int offset, int limit) {
        Objects.requireNonNull(player, "player");
        String needle = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        try {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object entry : orderedEntries(lineDatabase())) {
                Map.Entry<?, ?> nativeEntry = (Map.Entry<?, ?>) entry;
                UUID id = (UUID) nativeEntry.getKey();
                Object line = nativeEntry.getValue();
                Map<String, Object> row = lineSummary(id, line, player);
                if ((id + " " + row.get("name") + " " + row.get("description")).toLowerCase(Locale.ROOT).contains(needle)) out.add(row);
            }
            return page(out, offset, limit, "lines", query);
        } catch (ReflectiveOperationException ex) { throw unavailable(ex); }
    }

    /** Includes localized metadata, chapter position, prerequisite state and native task/reward NBT projections. */
    public Map<String, Object> observe(EntityPlayer player, String questId) {
        Objects.requireNonNull(player, "player");
        UUID id = uuid(questId);
        try {
            Object quest = requireQuest(id);
            UUID user = questingUuid(player);
            Map<String, Object> out = summary(id, quest, player);
            out.put("chapters", chaptersFor(id));
            out.put("requirements", requirements(quest, player));
            out.put("tasks", taskStates(quest, user, player));
            out.put("rewards", rewardStates(id, quest, player));
            out.put("completion", nbt(safeInvoke(quest, "getCompletionInfo", user)));
            return out;
        } catch (ReflectiveOperationException ex) { throw unavailable(ex); }
    }

    /**
     * Queues Better Questing's ordinary full-definition/progress and chapter query packets. This asks the server
     * to synchronize the book; it does not open a GUI, load a book item, or change quest/player state.
     */
    public Map<String, Object> sync() {
        try {
            Class.forName(QUEST_SYNC).getMethod("requestSync", Collection.class, boolean.class, boolean.class)
                    .invoke(null, (Object) null, true, true);
            Class.forName(CHAPTER_SYNC).getMethod("requestSync", Collection.class).invoke(null, (Object) null);
            return map("accepted", true, "nativeMethods", List.of("NetQuestSync.requestSync", "NetChapterSync.requestSync"),
                    "requestScope", "all_quests_and_lines", "serverAcknowledged", false,
                    "receipt", "native sync query packets queued; pending server processing and client synchronization");
        } catch (ReflectiveOperationException ex) { throw unavailable(ex); }
    }

    /**
     * Queues BQ's normal quest-scoped detect packet, after clicking each unfinished checkbox task the way the
     * book's button does (its own packet). taskIds narrow which checkboxes are clicked (none given: all of the
     * quest's); detection itself is quest-wide and this method never mutates a task directly.
     */
    public Map<String, Object> detect(EntityPlayer player, String questId, List<Integer> taskIds) {
        Objects.requireNonNull(player, "player"); Objects.requireNonNull(taskIds, "taskIds");
        UUID id = uuid(questId);
        List<Integer> clicked = new ArrayList<>();
        try {
            UUID user = questingUuid(player);
            for (Object entry : entries(taskDatabase(requireQuest(id)))) {
                int taskId = (Integer) invoke(entry, "getID"); Object task = invoke(entry, "getValue");
                if (!taskIds.isEmpty() && !taskIds.contains(taskId)) continue;
                if (!task.getClass().getName().equals(CHECKBOX_TASK) || Boolean.TRUE.equals(invoke(task, "isComplete", UUID.class, user))) continue;
                Class.forName(CHECKBOX_ACTION).getMethod("requestClick", UUID.class, int.class).invoke(null, id, taskId);
                clicked.add(taskId);
            }
        } catch (ReflectiveOperationException ex) { throw unavailable(ex); }
        Map<String, Object> receipt = action(player, questId, taskIds, List.of(), "requestDetect");
        receipt.put("checkboxesClicked", clicked);
        return receipt;
    }

    /** Parses explicit native reward choices written as {@code rewardId:choiceIndex}. */
    public Map<String, Object> claim(EntityPlayer player, String questId, List<Integer> rewardIds, List<String> choiceIds) {
        Map<Integer, Integer> choices = new LinkedHashMap<>();
        for (String raw : choiceIds) {
            int[] parsed = choiceId(raw);
            if (choices.put(parsed[0], parsed[1]) != null) throw new IllegalArgumentException("duplicate choice reward id " + parsed[0]);
        }
        return claim(player, questId, rewardIds, choices);
    }

    /**
     * Selects every supplied StandardExpansion choice through its own normal packet, then queues a normal quest
     * claim. The returned receipt is intentionally pending: a later {@link #observe(EntityPlayer, String)} is the
     * client-synchronized status; queueing a packet is not server acknowledgement.
     */
    public Map<String, Object> claim(EntityPlayer player, String questId, List<Integer> rewardIds, Map<Integer, Integer> choices) {
        Objects.requireNonNull(choices, "choices");
        UUID id = uuid(questId);
        try {
            Object quest = requireQuest(id);
            requireKnownIds(rewardDatabase(quest), rewardIds, "reward");
            validateChoices(id, quest, player, choices, true);
            for (Map.Entry<Integer, Integer> choice : choices.entrySet()) requestChoice(id, choice.getKey(), choice.getValue());
            Map<String, Object> receipt = action(player, questId, List.of(), rewardIds, "requestClaim");
            receipt.put("choices", choiceProjection(choices));
            receipt.put("receipt", "choice packets and claim packet queued; pending client synchronization; re-observe quest and inventory");
            return receipt;
        } catch (ReflectiveOperationException ex) { throw unavailable(ex); }
    }

    /** Queues only BQ StandardExpansion's native choice packet after checking the exact reward and option index. */
    public Map<String, Object> selectChoice(EntityPlayer player, String questId, int rewardId, int choiceIndex) {
        Objects.requireNonNull(player, "player");
        UUID id = uuid(questId);
        try {
            Object quest = requireQuest(id);
            validateChoices(id, quest, player, Map.of(rewardId, choiceIndex), false);
            requestChoice(id, rewardId, choiceIndex);
            return map("accepted", true, "nativeMethod", "NetRewardChoice.requestChoice", "questId", id.toString(),
                    "rewardId", rewardId, "choiceIndex", choiceIndex, "requestScope", "choice", "serverAcknowledged", false,
                    "receipt", "packet_queued; pending client synchronization; re-observe reward selection before claim");
        } catch (ReflectiveOperationException ex) { throw unavailable(ex); }
    }

    private Map<String, Object> action(EntityPlayer player, String questId, List<Integer> taskIds,
                                       List<Integer> rewardIds, String nativeMethod) {
        Objects.requireNonNull(player, "player"); Objects.requireNonNull(taskIds, "taskIds"); Objects.requireNonNull(rewardIds, "rewardIds");
        UUID id = uuid(questId);
        try {
            Object quest = requireQuest(id);
            if (nativeMethod.equals("requestDetect")) requireKnownIds(taskDatabase(quest), taskIds, "task");
            if (nativeMethod.equals("requestClaim")) requireKnownIds(rewardDatabase(quest), rewardIds, "reward");
            Class.forName(ACTIONS).getMethod(nativeMethod, Collection.class).invoke(null, List.of(id));
            return map("accepted", true, "nativeMethod", nativeMethod, "questId", id.toString(),
                    "taskIds", List.copyOf(taskIds), "rewardIds", List.copyOf(rewardIds), "requestScope", "quest",
                    "serverAcknowledged", false, "receipt", "packet_queued; pending client synchronization; re-observe quest progress");
        } catch (ReflectiveOperationException ex) { throw unavailable(ex); }
    }

    private List<Map<String, Object>> summaries(EntityPlayer player, String query) {
        Objects.requireNonNull(player, "player");
        String needle = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        try {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map.Entry<?, ?> entry : questDatabase().entrySet()) {
                UUID id = (UUID) entry.getKey(); Map<String, Object> row = summary(id, entry.getValue(), player);
                if ((id + " " + row.get("name") + " " + row.get("description")).toLowerCase(Locale.ROOT).contains(needle)) out.add(row);
            }
            out.sort(Comparator.<Map<String, Object>, String>comparing(value -> (String) value.get("name"), String.CASE_INSENSITIVE_ORDER).thenComparing(value -> (String) value.get("id")));
            return out;
        } catch (ReflectiveOperationException ex) { throw unavailable(ex); }
    }

    private Map<String, Object> summary(UUID id, Object quest, EntityPlayer player) throws ReflectiveOperationException {
        UUID user = questingUuid(player);
        return map("id", id.toString(), "name", questName(id, quest), "description", questDescription(id, quest),
                "state", String.valueOf(invoke(quest, "getState", EntityPlayer.class, player)),
                "unlocked", invoke(quest, "isUnlocked", UUID.class, user), "complete", invoke(quest, "isComplete", UUID.class, user),
                "claimed", invoke(quest, "hasClaimed", UUID.class, user), "canClaim", invoke(quest, "canClaim", EntityPlayer.class, player));
    }

    private Map<String, Object> lineSummary(UUID id, Object line, EntityPlayer player) throws ReflectiveOperationException {
        int completed = 0, unlocked = 0, unclaimed = 0, locked = 0;
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) line).entrySet()) {
            UUID questId = (UUID) entry.getKey(); Object quest = questDatabase().get(questId); if (quest == null) continue;
            String state = String.valueOf(invoke(quest, "getState", EntityPlayer.class, player));
            if (state.equals("COMPLETED")) completed++; else if (state.equals("UNLOCKED")) unlocked++; else if (state.equals("UNCLAIMED")) unclaimed++; else locked++;
            Object position = entry.getValue();
            entries.add(map("questId", questId.toString(), "x", invoke(position, "getPosX"), "y", invoke(position, "getPosY"), "sizeX", invoke(position, "getSizeX"), "sizeY", invoke(position, "getSizeY"), "state", state));
        }
        return map("id", id.toString(), "name", lineName(id, line), "description", lineDescription(id, line), "quests", entries.size(),
                "completed", completed, "unlocked", unlocked, "unclaimed", unclaimed, "locked", locked, "entries", List.copyOf(entries));
    }

    private List<Map<String, Object>> chaptersFor(UUID questId) throws ReflectiveOperationException {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object entry : orderedEntries(lineDatabase())) {
            Map.Entry<?, ?> nativeEntry = (Map.Entry<?, ?>) entry;
            UUID lineId = (UUID) nativeEntry.getKey(); Object line = nativeEntry.getValue(); Object position = ((Map<?, ?>) line).get(questId);
            if (position != null) out.add(map("id", lineId.toString(), "name", lineName(lineId, line), "x", invoke(position, "getPosX"), "y", invoke(position, "getPosY")));
        }
        return out;
    }

    private List<Map<String, Object>> requirements(Object quest, EntityPlayer player) throws ReflectiveOperationException {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object raw : (Collection<?>) invoke(quest, "getRequirements")) {
            UUID id = (UUID) raw; Object required = questDatabase().get(id);
            out.add(required == null ? map("id", id.toString(), "missing", true) : map("id", id.toString(), "name", questName(id, required), "state", String.valueOf(invoke(required, "getState", EntityPlayer.class, player))));
        }
        return out;
    }

    private List<Map<String, Object>> taskStates(Object quest, UUID user, EntityPlayer player) throws ReflectiveOperationException {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object entry : entries(taskDatabase(quest))) {
            int id = (Integer) invoke(entry, "getID"); Object task = invoke(entry, "getValue");
            Map<String, Object> row = map("id", id, "type", String.valueOf(invoke(task, "getFactoryID")), "name", translate(String.valueOf(invoke(task, "getUnlocalisedName"))),
                    "complete", invoke(task, "isComplete", UUID.class, user), "config", writeNbt(task, "writeToNBT", null), "progress", writeNbt(task, "writeProgressToNBT", user));
            List<Map<String, Object>> items = haveVersusNeed(task, user, player);
            if (items != null) row.put("items", items);
            out.add(row);
        }
        return out;
    }

    /**
     * For a retrieval task: each required item with need, what the task has already been given (submitted), and how
     * many you carry that BQ's own matcher accepts (have). Null for any other task.
     */
    private List<Map<String, Object>> haveVersusNeed(Object task, UUID user, EntityPlayer player) {
        try {
            Class<?> type = task.getClass();
            List<?> required = (List<?>) type.getField("requiredItems").get(task);
            boolean nbt = !type.getField("ignoreNBT").getBoolean(task), partial = type.getField("partialMatch").getBoolean(task);
            Object raw = type.getMethod("getUsersProgress", UUID.class).invoke(task, user);
            int[] given = raw instanceof int[] ints ? ints : new int[0];
            Class<?> compare = Class.forName("betterquesting.api.utils.ItemComparison"), ingredient = Class.forName("betterquesting.api2.utils.OreIngredient");
            Class<?> tag = Class.forName("net.minecraft.nbt.NBTTagCompound");
            Method stackMatch = compare.getMethod("StackMatch", ItemStack.class, ItemStack.class, boolean.class, boolean.class);
            Method oreMatch = compare.getMethod("OreDictionaryMatch", ingredient, tag, ItemStack.class, boolean.class, boolean.class);
            List<Map<String, Object>> out = new ArrayList<>();
            for (int i = 0; i < required.size(); i++) {
                Object big = required.get(i); ItemStack base = (ItemStack) invoke(big, "getBaseStack");
                Object ore = invoke(big, "getOreIngredient"), bigTag = invoke(big, "GetTagCompound");
                int have = 0;
                for (ItemStack stack : player.inventory.mainInventory) {
                    if (stack == null) continue;
                    if (Boolean.TRUE.equals(stackMatch.invoke(null, base, stack, nbt, partial)) || Boolean.TRUE.equals(oreMatch.invoke(null, ore, bigTag, stack, nbt, partial))) have += stack.stackSize;
                }
                String name; try { name = base.getDisplayName(); } catch (RuntimeException ex) { name = String.valueOf(Item.itemRegistry.getNameForObject(base.getItem())); }
                Object oreName = invoke(big, "getOreDict");
                Map<String, Object> row = map("name", name, "need", big.getClass().getField("stackSize").getInt(big), "submitted", i < given.length ? given[i] : 0, "have", have);
                if (oreName != null && !String.valueOf(oreName).isEmpty()) row.put("oreDict", String.valueOf(oreName));
                out.add(row);
            }
            return out;
        } catch (NoSuchFieldException ex) { return null; }
        catch (ReflectiveOperationException | RuntimeException | LinkageError ex) { return List.of(map("unavailable", ex.getClass().getSimpleName() + ": " + ex.getMessage())); }
    }

    private List<Map<String, Object>> rewardStates(UUID questId, Object quest, EntityPlayer player) throws ReflectiveOperationException {
        List<Map<String, Object>> out = new ArrayList<>();
        UUID user = questingUuid(player);
        for (Object entry : entries(rewardDatabase(quest))) {
            int id = (Integer) invoke(entry, "getID"); Object reward = invoke(entry, "getValue");
            Map<String, Object> row = map("id", id, "type", String.valueOf(invoke(reward, "getFactoryID")), "name", translate(String.valueOf(invoke(reward, "getUnlocalisedName"))),
                    "canClaim", invoke(reward, "canClaim", EntityPlayer.class, player, Map.Entry.class, Map.entry(questId, quest)), "config", writeNbt(reward, "writeToNBT", null));
            if (reward.getClass().getName().equals(CHOICE_REWARD)) {
                List<Map<String, Object>> options = new ArrayList<>();
                List<?> choices = (List<?>) reward.getClass().getField("choices").get(reward);
                for (int index = 0; index < choices.size(); index++) options.add(map("choiceIndex", index, "stack", bigStack(choices.get(index))));
                row.put("choice", map("selectedIndex", invoke(reward, "getSelecton", UUID.class, user), "options", List.copyOf(options)));
            }
            out.add(row);
        }
        return out;
    }

    private void validateChoices(UUID questId, Object quest, EntityPlayer player, Map<Integer, Integer> requested, boolean requireEveryChoice) throws ReflectiveOperationException {
        Map<Integer, Object> rewards = new LinkedHashMap<>();
        for (Object entry : entries(rewardDatabase(quest))) rewards.put((Integer) invoke(entry, "getID"), invoke(entry, "getValue"));
        for (Map.Entry<Integer, Integer> choice : requested.entrySet()) {
            Object reward = rewards.get(choice.getKey());
            if (reward == null) throw new IllegalArgumentException("unknown reward id " + choice.getKey());
            if (!reward.getClass().getName().equals(CHOICE_REWARD)) throw new IllegalArgumentException("reward " + choice.getKey() + " is not a StandardExpansion choice reward");
            List<?> options = (List<?>) reward.getClass().getField("choices").get(reward);
            if (choice.getValue() == null || choice.getValue() < 0 || choice.getValue() >= options.size()) throw new IllegalArgumentException("choice index outside native options for reward " + choice.getKey());
        }
        if (requireEveryChoice) for (Map.Entry<Integer, Object> reward : rewards.entrySet()) if (reward.getValue().getClass().getName().equals(CHOICE_REWARD) && !requested.containsKey(reward.getKey())) throw new IllegalArgumentException("explicit choice required for reward " + reward.getKey());
    }

    private void requestChoice(UUID questId, int rewardId, int choiceIndex) throws ReflectiveOperationException {
        Class.forName(CHOICE_ACTION).getMethod("requestChoice", UUID.class, int.class, int.class).invoke(null, questId, rewardId, choiceIndex);
    }

    private static List<Map<String, Object>> choiceProjection(Map<Integer, Integer> choices) {
        List<Map<String, Object>> out = new ArrayList<>(); for (Map.Entry<Integer, Integer> entry : choices.entrySet()) out.add(map("rewardId", entry.getKey(), "choiceIndex", entry.getValue())); return out;
    }
    static int[] choiceId(String raw) {
        if (raw == null) throw new IllegalArgumentException("choice id must be rewardId:choiceIndex");
        String[] parts = raw.split(":", -1); if (parts.length != 2) throw new IllegalArgumentException("choice id must be rewardId:choiceIndex");
        try { int reward = Integer.parseInt(parts[0]); int choice = Integer.parseInt(parts[1]); if (reward < 0 || choice < 0) throw new NumberFormatException(); return new int[]{reward, choice}; }
        catch (NumberFormatException ex) { throw new IllegalArgumentException("choice id must be non-negative rewardId:choiceIndex", ex); }
    }

    private String questName(UUID id, Object quest) { return translateQuest("translateQuestName", id, quest, property(quest, "NAME", "")); }
    private String questDescription(UUID id, Object quest) { return translateQuest("translateQuestDescription", id, quest, property(quest, "DESC", "")); }
    private String lineName(UUID id, Object line) { return translateQuest("translateQuestLineName", id, line, property(line, "NAME", "")); }
    private String lineDescription(UUID id, Object line) { return translateQuest("translateQuestLineDescription", id, line, property(line, "DESC", "")); }
    private String translateQuest(String method, UUID id, Object subject, Object fallback) {
        try {
            for (Method candidate : Class.forName(TRANSLATION).getMethods()) {
                Class<?>[] types = candidate.getParameterTypes();
                if (candidate.getName().equals(method) && types.length == 2 && types[0] == UUID.class && types[1].isInstance(subject)) {
                    return String.valueOf(candidate.invoke(null, id, subject));
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
        return translate(String.valueOf(fallback));
    }
    private String translate(String raw) {
        try { return String.valueOf(Class.forName(TRANSLATION).getMethod("translate", String.class, Object[].class).invoke(null, raw, new Object[0])); }
        catch (ReflectiveOperationException ex) { return raw; }
    }
    private Object property(Object value, String field, Object fallback) {
        try { Object property = Class.forName(NATIVE_PROPS).getField(field).get(null); for (Method method : value.getClass().getMethods()) if (method.getName().equals("getProperty") && method.getParameterCount() == 2) return method.invoke(value, property, fallback); }
        catch (ReflectiveOperationException ignored) { } return fallback;
    }
    private Object writeNbt(Object source, String method, UUID user) {
        try {
            Class<?> tag = Class.forName("net.minecraft.nbt.NBTTagCompound"); Object out = tag.newInstance();
            Object result = user == null ? source.getClass().getMethod(method, tag).invoke(source, out) : source.getClass().getMethod(method, tag, List.class).invoke(source, out, List.of(user));
            return nbt(result);
        } catch (ReflectiveOperationException ex) { return map("available", false, "reason", ex.getClass().getSimpleName() + ": " + ex.getMessage()); }
    }
    private Object bigStack(Object big) {
        try {
            Object base = big.getClass().getMethod("getBaseStack").invoke(big);
            int count = big.getClass().getField("stackSize").getInt(big);
            if (!(base instanceof ItemStack stack)) return map("count", count, "stack", null);
            return map("id", Item.itemRegistry.getNameForObject(stack.getItem()), "meta", stack.getItemDamage(),
                    "nbt", stack.hasTagCompound() ? stack.getTagCompound().toString() : null, "count", count);
        }
        catch (ReflectiveOperationException ex) { return map("unavailable", ex.getClass().getSimpleName()); }
    }
    private static Object nbt(Object nbt) { return nbt == null ? null : nbt.toString(); }

    private Object taskDatabase(Object quest) throws ReflectiveOperationException { return invoke(quest, "getTasks"); }
    private Object rewardDatabase(Object quest) throws ReflectiveOperationException { return invoke(quest, "getRewards"); }
    private List<?> entries(Object database) throws ReflectiveOperationException { return (List<?>) invoke(database, "getEntries"); }
    private List<?> orderedEntries(Object database) throws ReflectiveOperationException { return (List<?>) invoke(database, "getOrderedEntries"); }
    private void requireKnownIds(Object database, List<Integer> requested, String kind) throws ReflectiveOperationException {
        if (requested.isEmpty()) throw new IllegalArgumentException(kind + "Ids must be non-empty"); List<Integer> known = new ArrayList<>();
        for (Object entry : entries(database)) known.add((Integer) invoke(entry, "getID"));
        for (Integer id : requested) if (id == null || !known.contains(id)) throw new IllegalArgumentException("unknown " + kind + " id " + id);
    }
    @SuppressWarnings("unchecked") private Map<?, ?> questDatabase() throws ReflectiveOperationException { return (Map<?, ?>) api("QUEST_DB"); }
    @SuppressWarnings("unchecked") private Map<?, ?> lineDatabase() throws ReflectiveOperationException { return (Map<?, ?>) api("LINE_DB"); }
    private Object api(String field) throws ReflectiveOperationException { Object key = Class.forName(API_REFERENCE).getField(field).get(null); return Class.forName(QUESTING_API).getMethod("getAPI", Class.forName("betterquesting.api.api.ApiKey")).invoke(null, key); }
    private Object requireQuest(UUID id) throws ReflectiveOperationException { Object quest = questDatabase().get(id); if (quest == null) throw new IllegalArgumentException("unknown quest id " + id); return quest; }
    private UUID questingUuid(EntityPlayer player) throws ReflectiveOperationException { return (UUID) Class.forName(QUESTING_API).getMethod("getQuestingUUID", EntityPlayer.class).invoke(null, player); }
    private static UUID uuid(String raw) { try { return UUID.fromString(raw); } catch (RuntimeException ex) { throw new IllegalArgumentException("questId must be a UUID", ex); } }

    static Map<String, Object> page(List<Map<String, Object>> values, int offset, int limit, String key, String query) {
        if (offset < 0 || limit < 1 || limit > 100) throw new IllegalArgumentException("offset must be >=0 and limit 1..100"); int from = Math.min(offset, values.size()), to = Math.min(from + limit, values.size());
        return map(key, List.copyOf(values.subList(from, to)), "offset", offset, "limit", limit, "total", values.size(), "nextOffset", to < values.size() ? to : null, "query", query == null ? "" : query);
    }
    private static Object invoke(Object target, String name, Class<?>... types) throws ReflectiveOperationException { return target.getClass().getMethod(name, types).invoke(target); }
    private static Object invoke(Object target, String name, Class<?> first, Object firstValue) throws ReflectiveOperationException { return target.getClass().getMethod(name, first).invoke(target, firstValue); }
    private static Object invoke(Object target, String name, Class<?> first, Object firstValue, Class<?> second, Object secondValue) throws ReflectiveOperationException { return target.getClass().getMethod(name, first, second).invoke(target, firstValue, secondValue); }
    private static Object safeInvoke(Object target, String name, Object argument) { try { return invoke(target, name, UUID.class, argument); } catch (ReflectiveOperationException ex) { return null; } }
    private static IllegalStateException unavailable(Exception ex) { return new IllegalStateException("BetterQuesting API unavailable", ex); }
    private static Map<String, Object> map(Object... values) { Map<String, Object> map = new LinkedHashMap<>(); for (int i = 0; i < values.length; i += 2) map.put((String) values[i], values[i + 1]); return map; }
}
