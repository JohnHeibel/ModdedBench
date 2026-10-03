// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.api;

/** Client input ownership: leases, key/mouse state and owned screens. */
public interface Controls {
    InputArbiter arbiter();
    void focusForInput();
    /** Locks a raw attack hold to the block now under the crosshair; returns its [x,y,z], or null when there is none. */
    int[] guardBlockAttack(InputArbiter.Lease lease);
    boolean blockAttackChanged(InputArbiter.Lease lease);
    void releaseBlockAttack(InputArbiter.Lease lease);
    InventorySession beginPlayerInventory(InputArbiter.Lease lease);
    boolean ownsPlayerInventory(InputArbiter.Lease lease);
    /** {@code screen} is a net.minecraft.client.gui.GuiScreen. */
    void ownGui(InputArbiter.Lease lease,Object screen);
    void releaseGui(InputArbiter.Lease lease);
    interface InventorySession extends AutoCloseable {
        boolean isOpen();
        @Override void close();
    }
}
