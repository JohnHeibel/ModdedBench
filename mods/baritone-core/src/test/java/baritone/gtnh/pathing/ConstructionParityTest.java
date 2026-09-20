// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh.pathing;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Masks below deliberately mirror the retained upstream SphereMask and
 * CylinderMask equations, rather than an intuitive "round" approximation.
 */
public class ConstructionParityTest {
    private static Map<String,Object> selection(String shape, String axis, int maxX, int maxY, int maxZ) {
        Map<String,Object> sel = new LinkedHashMap<>();
        sel.put("min", List.of(0, 10, 0));
        sel.put("max", List.of(maxX, 10 + maxY, maxZ));
        sel.put("shape", shape);
        if (axis != null) sel.put("axis", axis);
        sel.put("block", Map.of("id", "minecraft:stone", "meta", 3));
        return Map.of("selection", sel);
    }

    private static Set<BlockPos> actual(Map<String,Object> spec) {
        Set<BlockPos> result = new LinkedHashSet<>();
        for (WorkSpec.Cell cell : WorkSpec.cells(spec)) {
            assertEquals("shape must retain exact requested metadata", 3, cell.meta());
            result.add(cell.pos());
        }
        return result;
    }

    // Source parity oracle: src/api/.../mask/shape/SphereMask.java.
    private static Set<BlockPos> upstreamSphere(int width, int height, int length, boolean filled) {
        double cx = width / 2.0, cy = height / 2.0, cz = length / 2.0;
        Set<BlockPos> result = new LinkedHashSet<>();
        for (int x = 0; x < width; x++) for (int y = 0; y < height; y++) for (int z = 0; z < length; z++) {
            double dx = Math.abs(x + .5 - cx), dy = Math.abs(y + .5 - cy), dz = Math.abs(z + .5 - cz);
            if (sphereOutside(dx, dy, dz, cx * cx, cy * cy, cz * cz)) continue;
            if (filled || sphereOutside(dx + 1, dy, dz, cx * cx, cy * cy, cz * cz)
                    || sphereOutside(dx, dy + 1, dz, cx * cx, cy * cy, cz * cz)
                    || sphereOutside(dx, dy, dz + 1, cx * cx, cy * cy, cz * cz)) {
                result.add(new BlockPos(x, y + 10, z));
            }
        }
        return result;
    }

    private static boolean sphereOutside(double a, double b, double c, double aa, double bb, double cc) {
        return a * a / aa + b * b / bb + c * c / cc > 1;
    }

    // Source parity oracle: src/api/.../mask/shape/CylinderMask.java.
    private static Set<BlockPos> upstreamCylinder(int width, int height, int length, String axis, boolean filled) {
        int aSize = axis.equals("x") ? height : width;
        int bSize = axis.equals("z") ? height : length;
        double centerA = aSize / 2.0, centerB = bSize / 2.0;
        double radiusA = (centerA - 1) * (centerA - 1), radiusB = (centerB - 1) * (centerB - 1);
        Set<BlockPos> result = new LinkedHashSet<>();
        for (int x = 0; x < width; x++) for (int y = 0; y < height; y++) for (int z = 0; z < length; z++) {
            int av = axis.equals("x") ? y : x;
            int bv = axis.equals("z") ? y : z;
            double da = Math.abs(av + .5 - centerA), db = Math.abs(bv + .5 - centerB);
            if (cylinderOutside(da, db, radiusA, radiusB)) continue;
            if (filled || cylinderOutside(da + 1, db, radiusA, radiusB)
                    || cylinderOutside(da, db + 1, radiusA, radiusB)) {
                result.add(new BlockPos(x, y + 10, z));
            }
        }
        return result;
    }

    private static boolean cylinderOutside(double a, double b, double aa, double bb) {
        return a * a / aa + b * b / bb > 1;
    }

    @Test public void filledAndHollowSphereMatchUpstreamMask() {
        assertEquals(upstreamSphere(5, 5, 5, true), actual(selection("sphere", null, 4, 4, 4)));
        assertEquals(upstreamSphere(5, 5, 5, false), actual(selection("hsphere", null, 4, 4, 4)));
        assertTrue(actual(selection("sphere", null, 4, 4, 4)).contains(new BlockPos(2, 12, 2)));
        assertFalse(actual(selection("hsphere", null, 4, 4, 4)).contains(new BlockPos(2, 12, 2)));
    }

    @Test public void filledAndHollowCylinderMatchUpstreamMaskForEveryAxis() {
        for (String axis : List.of("x", "y", "z")) {
            assertEquals(axis + " filled", upstreamCylinder(7, 5, 5, axis, true), actual(selection("cylinder", axis, 6, 4, 4)));
            assertEquals(axis + " hollow", upstreamCylinder(7, 5, 5, axis, false), actual(selection("hcylinder", axis, 6, 4, 4)));
        }
    }

    @Test public void cylinderDefaultsToUpstreamYAxisAndRejectsUnknownAxis() {
        assertEquals(actual(selection("cylinder", "y", 6, 4, 4)), actual(selection("cylinder", null, 6, 4, 4)));
        try { WorkSpec.cells(selection("cylinder", "diagonal", 6, 4, 4)); fail("unknown cylinder axis accepted"); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("axis")); }
    }
}
