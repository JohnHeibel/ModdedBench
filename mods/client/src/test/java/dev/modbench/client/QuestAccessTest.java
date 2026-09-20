// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.client;

import org.junit.Test;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class QuestAccessTest {
    @Test public void paginationIsBoundedAndKeepsStableOffsets() {
        List<Map<String,Object>> values=List.of(Map.of("id","a"),Map.of("id","b"),Map.of("id","c"));
        Map<String,Object> page=QuestAccess.page(values,1,1,"quests","ore");
        assertEquals(List.of(Map.of("id","b")),page.get("quests"));
        assertEquals(2,page.get("nextOffset")); assertEquals(3,page.get("total"));
    }
    @Test public void paginationMarksLastPageWithoutInventingCursor() {
        Map<String,Object> page=QuestAccess.page(List.of(Map.of("id","a")),5,10,"lines","");
        assertEquals(List.of(),page.get("lines")); assertNull(page.get("nextOffset"));
    }
    @Test(expected=IllegalArgumentException.class) public void paginationRejectsUnboundedLimit() { QuestAccess.page(List.of(),0,101,"quests",""); }
    @Test public void choiceIdCarriesNativeRewardAndOptionIds() { assertArrayEquals(new int[]{17,2},QuestAccess.choiceId("17:2")); }
    @Test(expected=IllegalArgumentException.class) public void choiceIdRejectsAmbiguousSyntax() { QuestAccess.choiceId("17"); }
    @Test(expected=IllegalArgumentException.class) public void choiceIdRejectsNegativeNativeIds() { QuestAccess.choiceId("17:-1"); }
}