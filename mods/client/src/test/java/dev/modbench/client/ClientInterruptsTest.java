// SPDX-License-Identifier: MIT
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.client;

import com.google.gson.JsonObject;
import dev.modbench.bridge.Json;
import org.junit.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ClientInterruptsTest {
    @Test public void latchRefusalKeepsPrefixAndIdsAndCarriesReasonAndClippedPrompt() {
        Map<String,JsonObject> receipts=new LinkedHashMap<>();
        receipts.put("a",Json.object("reason","low health","payload",Json.object("modelPrompt","p".repeat(300),"count",2)));
        receipts.put("b",Json.object("reason","furnace done","payload",null));
        String text=ClientInterrupts.refusal(receipts.keySet(),receipts).getMessage();
        assertTrue(text,text.startsWith("interrupt_latched: [a, b] delivered, this action was not started; read them, then act (the latch is released); a: low health | "+"p".repeat(240)+"...; "));
        assertTrue(text,text.endsWith("; b: furnace done"));
    }
    @Test public void latchRefusalIsBounded() {
        Map<String,JsonObject> receipts=new LinkedHashMap<>();
        for(int i=0;i<20;i++) receipts.put("id"+i,Json.object("reason","r"+i));
        String text=ClientInterrupts.refusal(receipts.keySet(),receipts).getMessage();
        assertTrue(text,text.endsWith("; id7: r7; ...")); assertEquals(-1,text.indexOf("id8:"));
    }
}
