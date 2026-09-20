// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.api;
import org.junit.Test;
import static org.junit.Assert.*;

public class ClickReceiptTest {
    @Test public void acknowledgementRequiresEveryMatchingTransaction() {
        ClickReceipt r=new ClickReceipt();assertFalse(r.accepted());
        r.sent(1,(short)3);r.sent(1,(short)4);r.received(2,(short)3,true);r.received(1,(short)99,true);
        assertFalse(r.complete());r.received(1,(short)3,true);assertFalse(r.accepted());
        r.received(1,(short)4,true);assertTrue(r.accepted());
    }
    @Test public void rejectionAndTransactionWrapAreNotSuccess() {
        ClickReceipt r=new ClickReceipt();r.sent(1,Short.MIN_VALUE);r.received(1,Short.MIN_VALUE,false);
        assertTrue(r.complete());assertTrue(r.rejected());assertFalse(r.accepted());
        ClickReceipt reused=new ClickReceipt();reused.sent(1,(short)1);reused.sent(1,(short)1);reused.received(1,(short)1,true);assertFalse(reused.accepted());
    }
}
