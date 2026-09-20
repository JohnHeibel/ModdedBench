// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.compat;

/**
 * Version boundary for clutch placement/recovery. Providers must verify native
 * fluid/container capabilities. No item ID or assumed container capacity here.
 */
public final class FallProtection {
    public interface Provider {
        boolean available();
        boolean selectForPlacement();
        boolean selectForRecovery();
    }
    private static Provider provider;
    private FallProtection(){}
    public static void register(Provider value){provider=java.util.Objects.requireNonNull(value);}
    public static boolean available(){return provider!=null&&provider.available();}
    public static boolean selectForPlacement(){return available()&&provider.selectForPlacement();}
    public static boolean selectForRecovery(){return provider!=null&&provider.selectForRecovery();}
}
