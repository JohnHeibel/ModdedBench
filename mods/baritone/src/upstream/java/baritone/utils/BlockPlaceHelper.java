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
import baritone.api.utils.IPlayerContext;
import baritone.compat.RayTraceResult;

public class BlockPlaceHelper {

    private final IPlayerContext ctx;
    private int rightClickTimer;

    BlockPlaceHelper(IPlayerContext playerContext) {
        this.ctx = playerContext;
    }

    public void tick(boolean rightClickRequested) {
        if (rightClickTimer > 0) {
            rightClickTimer--;
            return;
        }
        RayTraceResult mouseOver = ctx.objectMouseOver();
        if (!rightClickRequested || ctx.player().ridingEntity instanceof net.minecraft.entity.item.EntityBoat || mouseOver == null || mouseOver.getBlockPos() == null || mouseOver.typeOfHit != RayTraceResult.Type.BLOCK) {
            return;
        }
        rightClickTimer = Baritone.settings().rightClickSpeed.value;
        // Native 1.7 has one hand. Preserve block interaction before item-use fallback.
            if (ctx.playerController().processRightClickBlock(mouseOver.getBlockPos(), mouseOver.sideHit, mouseOver.hitVec)) {
                ctx.player().swingItem();
                return;
            }
            if (ctx.player().getHeldItem() != null && ctx.playerController().processRightClick()) {
                return;
            }
    }
}
