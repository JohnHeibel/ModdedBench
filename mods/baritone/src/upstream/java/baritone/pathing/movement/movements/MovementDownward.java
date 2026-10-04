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

package baritone.pathing.movement.movements;

import baritone.api.IBaritone;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.BetterBlockPos;
import baritone.gtnh.BlockShapes; // ModdedBench
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.compat.Vec3d;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.MovementState;
import com.google.common.collect.ImmutableSet;
import net.minecraft.block.Block;
import baritone.compat.IBlockState;
import baritone.compat.Blocks;

import java.util.Set;

public class MovementDownward extends Movement {

    private int numTicks = 0;

    public MovementDownward(IBaritone baritone, BetterBlockPos start, BetterBlockPos end) {
        super(baritone, start, end, new BetterBlockPos[]{end});
    }

    @Override
    public void reset() {
        super.reset();
        numTicks = 0;
    }

    @Override
    public double calculateCost(CalculationContext context) {
        return cost(context, src.x, src.y, src.z);
    }

    @Override
    protected Set<BetterBlockPos> calculateValidPositions() {
        return ImmutableSet.of(src, dest);
    }

    public static double cost(CalculationContext context, int x, int y, int z) {
        if (!context.allowDownward) {
            return COST_INF;
        }
        if (!MovementHelper.canWalkOn(context, x, y - 2, z)) {
            return COST_INF;
        }
        IBlockState down = context.get(x, y - 1, z);
        Block downBlock = down.getBlock();
        if (downBlock == Blocks.LADDER || downBlock == Blocks.VINE) {
            return LADDER_DOWN_ONE_COST;
        } else {
            // we're standing on it, while it might be block falling, it'll be air by the time we get here in the movement
            return FALL_N_BLOCKS_COST[1] + MovementHelper.getMiningDurationTicksFrom(context, x, y, z, x, y - 1, z, down, false);
        }
    }

    @Override
    public MovementState updateState(MovementState state) {
        super.updateState(state);
        if (state.getStatus() != MovementStatus.RUNNING) {
            return state;
        }

        if (ctx.playerFeet().equals(dest)) {
            return state.setStatus(MovementStatus.SUCCESS);
        } else if (!playerInValidPosition()) {
            return state.setStatus(MovementStatus.UNREACHABLE);
        }
        // ModdedBench: the way down is the room the cell below leaves, not its middle. A ladder's box takes a strip of its
        // cell; a body that came over the hole fast rests on the strip's top one cell down, and upstream then walked
        // on the way it faced, into the ladder, which climbs. Head for the room, and stand still once over it.
        net.minecraft.world.World world = ctx.world().nativeWorld;
        double[] roomX = BlockShapes.room(world, ctx.player(), dest.getX(), dest.getY(), dest.getZ(), false);
        double[] roomZ = BlockShapes.room(world, ctx.player(), dest.getX(), dest.getY(), dest.getZ(), true);
        double toX = over(roomX, dest.getX() + 0.5, ctx.player().posX);
        double toZ = over(roomZ, dest.getZ() + 0.5, ctx.player().posZ);
        double diffX = ctx.player().posX - toX;
        double diffZ = ctx.player().posZ - toZ;
        double ab = Math.sqrt(diffX * diffX + diffZ * diffZ);

        if (there(roomX, diffX) && there(roomZ, diffZ) || (numTicks++ < 10 && ab < 0.2)) {
            return state;
        }
        Rotation toward = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), new Vec3d(toX, ctx.playerHead().y, toZ), ctx.playerRotations());
        return state.setTarget(new MovementState.MovementTarget(toward.withPitch(ctx.playerRotations().getPitch()), false)).setInput(Input.MOVE_FORWARD, true);
    }

    /** ModdedBench: where along one axis to be: where the body is when that is in the room already (steering on would
     *  swing it from edge to edge), or the room's aim. */
    private static double over(double[] room, double mid, double at) {
        return room != null && at >= Math.max(room[0], mid - 0.5) && at <= Math.min(room[1], mid + 0.5) ? at : BlockShapes.aim(room, mid);
    }

    /** In the room; with none measured (a block still to dig), near the cell's middle as upstream had it. */
    private static boolean there(double[] room, double diff) {
        return room == null ? Math.abs(diff) < 0.05 : diff == 0;
    }
}
