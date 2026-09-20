// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh.pathing;

/** Traversal permission is distinct from physical properties and from bucket interaction. */
public final class FluidPolicy {
    private FluidPolicy() {}

    public static String reason(boolean vanillaWater, boolean lava, Integer temperatureK) {
        if (lava) return "lava";
        if (temperatureK != null && temperatureK >= 500) return "hot_fluid";
        if (vanillaWater) return "verified_water";
        // Material.water and room temperature say nothing about poison or custom collision effects.
        return "unverified_fluid";
    }

    public static double swimCost(double flowX, double flowZ, int dx, int dz) {
        double against = Math.max(0, -(flowX * dx + flowZ * dz));
        double cross = Math.abs(flowX * dz - flowZ * dx);
        // Never discount below the retained Baritone heuristic, even downstream.
        return ActionCosts.WALK_ONE_IN_WATER_COST * (1 + 1.8 * against + .5 * cross);
    }
}
