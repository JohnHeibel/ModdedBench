// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
package dev.modbench.control.api;

import java.util.*;

/** Tracks only this operation's native container transactions; observations are not acknowledgements. */
public final class ClickReceipt {
    private record Key(int window,short transaction) {}
    private final Map<Key,Boolean> transactions=new LinkedHashMap<>();
    private boolean overflow;
    public synchronized void sent(int window,short transaction) {
        Key key=new Key(window,transaction);
        if(transactions.size()>=512||transactions.containsKey(key)) {overflow=true;return;}
        transactions.put(key,null);
    }
    public synchronized void received(int window,short transaction,boolean accepted) {
        Key key=new Key(window,transaction);if(transactions.containsKey(key)) transactions.put(key,accepted);
    }
    public synchronized boolean complete() {return !overflow&&!transactions.isEmpty()&&transactions.values().stream().noneMatch(Objects::isNull);}
    public synchronized boolean accepted() {return complete()&&transactions.values().stream().allMatch(Boolean.TRUE::equals);}
    public synchronized boolean rejected() {return transactions.values().stream().anyMatch(Boolean.FALSE::equals);}
    public synchronized int count() {return transactions.size();}
    public synchronized Map<String,Object> status() {
        return Map.of("sent",transactions.size(),"accepted",transactions.values().stream().filter(Boolean.TRUE::equals).count(),
            "rejected",transactions.values().stream().filter(Boolean.FALSE::equals).count(),"pending",transactions.values().stream().filter(Objects::isNull).count(),
            "serverAcknowledged",accepted(),"overflow",overflow);
    }
}
