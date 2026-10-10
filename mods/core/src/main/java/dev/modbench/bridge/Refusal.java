// SPDX-License-Identifier: MIT
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.bridge;

/**
 * A refusal that names its own error code (time_paused, stale_stack, target_not_visible): the reply's code is that
 * code, not bad_request or the code of whatever caught it. The message keeps the code as its prefix, as it always read.
 */
public final class Refusal extends IllegalArgumentException {
    public final String code;
    public Refusal(String code, String detail) { super(code+": "+detail); this.code=code; }
    @Override public String toString() { return getMessage(); }
}
