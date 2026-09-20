// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.compat;

/** Shared gate for the native Minecraft attack loop while a source process owns input. */
public final class ActionHooks {
    private ActionHooks() {}
    public static boolean ownsNativeActions() {
        return baritone.Baritone.initialized() && baritone.Baritone.instance().getInputOverrideHandler().hasActiveLease();
    }
}
