// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
package dev.modbench.api;

/** Client input ownership: leases, key/mouse state and owned screens. */
public interface Controls {
    InputArbiter arbiter();
    void focusForInput();
    void guardBlockAttack(InputArbiter.Lease lease);
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
