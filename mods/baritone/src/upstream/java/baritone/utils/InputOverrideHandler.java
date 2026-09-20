/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Modified by the ModdedBench project (2026) for Minecraft 1.7.10 / GT New Horizons.
 * The original file and its SHA-256 are recorded in META-INF/modbench/UPSTREAM_SOURCES.json.
 */

package baritone.utils;

import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.api.event.events.TickEvent;
import baritone.api.utils.IInputOverrideHandler;
import baritone.api.utils.input.Input;
import baritone.behavior.Behavior;
import dev.modbench.api.InputArbiter;
import java.util.*;

import java.util.HashMap;
import java.util.Map;

/**
 * An interface with the game's control system allowing the ability to
 * force down certain controls, having the same effect as if we were actually
 * physically forcing down the assigned key.
 *
 * @author Brady
 * @since 7/31/2018
 */
public final class InputOverrideHandler extends Behavior implements IInputOverrideHandler {

    /**
     * Maps inputs to whether or not we are forcing their state down.
     */
    private final Map<Input, Boolean> inputForceStateMap = new HashMap<>();

    private final BlockBreakHelper blockBreakHelper;
    private final BlockPlaceHelper blockPlaceHelper;

    public InputOverrideHandler(Baritone baritone) {
        super(baritone);
        this.blockBreakHelper = new BlockBreakHelper(baritone.getPlayerContext());
        this.blockPlaceHelper = new BlockPlaceHelper(baritone.getPlayerContext());
    }

    /**
     * Returns whether or not we are forcing down the specified {@link Input}.
     *
     * @param input The input
     * @return Whether or not it is being forced down
     */
    @Override
    public final boolean isInputForcedDown(Input input) {
        return input == null ? false : this.inputForceStateMap.getOrDefault(input, false);
    }

    /**
     * Sets whether or not the specified {@link Input} is being forced down.
     *
     * @param input  The {@link Input}
     * @param forced Whether or not the state is being forced
     */
    @Override
    public final void setInputForceState(Input input, boolean forced) {
        this.inputForceStateMap.put(input, forced);
    }

    /**
     * Clears the override state for all keys
     */
    @Override
    public final void clearAllKeys() {
        this.inputForceStateMap.clear();
    }

    private InputArbiter.Lease lease;

    public boolean hasActiveLease() { return lease != null && lease.isActive(); }
    public InputArbiter.Lease lease(){return lease;}
    public void attach(InputArbiter.Lease lease) {
        release();
        this.lease = Objects.requireNonNull(lease);
    }

    /** Publish once after source movement and aiming have finished for the tick. */
    public void flush() {
        if (!hasActiveLease()) { blockBreakHelper.stopBreakingBlock(); return; }
        if(baritone.getInventoryBehavior().swapping()){lease.setKeys(Set.of());blockBreakHelper.stopBreakingBlock();return;}
        if (isInputForcedDown(Input.CLICK_LEFT)) setInputForceState(Input.CLICK_RIGHT, false);
        if(Baritone.settings().freeLook.value)lease.clearLook();
        else lease.look(ctx.player().rotationYaw, ctx.player().rotationPitch);
        Set<Integer> keys = new LinkedHashSet<>();
        var settings = ctx.minecraft().gameSettings;
        for (Input input : Input.values()) {
            // The original helpers own block clicks; do not also dispatch vanilla clicks.
            if (!isInputForcedDown(input) || input == Input.CLICK_LEFT || input == Input.CLICK_RIGHT) continue;
            keys.add(switch(input) {
                case MOVE_FORWARD -> settings.keyBindForward.getKeyCode();
                case MOVE_BACK -> settings.keyBindBack.getKeyCode();
                case MOVE_LEFT -> settings.keyBindLeft.getKeyCode();
                case MOVE_RIGHT -> settings.keyBindRight.getKeyCode();
                case JUMP -> settings.keyBindJump.getKeyCode();
                case SNEAK -> settings.keyBindSneak.getKeyCode();
                case SPRINT -> settings.keyBindSprint.getKeyCode();
                default -> throw new IllegalStateException("Unexpected input " + input);
            });
        }
        lease.setKeys(keys);
        blockBreakHelper.tick(isInputForcedDown(Input.CLICK_LEFT));
        if (hasActiveLease()) blockPlaceHelper.tick(isInputForcedDown(Input.CLICK_RIGHT) && !baritone.getInventoryBehavior().selectionPending()
            && (!isInputForcedDown(Input.SNEAK)||ctx.player().isSneaking()));
    }

    public void release() {
        clearAllKeys();
        blockBreakHelper.stopBreakingBlock();
        if (hasActiveLease()) lease.setKeys(Set.of());
        lease = null;
        baritone.getInventoryBehavior().resetRequests();
    }

    public BlockBreakHelper getBlockBreakHelper() {
        return blockBreakHelper;
    }
}
