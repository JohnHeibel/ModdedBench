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

package baritone.process;

import baritone.Baritone;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.MovementState;
import baritone.pathing.path.PathExecutor;
import baritone.utils.BaritoneProcessHelper;
import baritone.compat.IBlockState;
import baritone.compat.Blocks;
import baritone.compat.EnumFacing;
import baritone.compat.BlockPos;
import net.minecraft.world.chunk.EmptyChunk;

import java.util.*;
import java.util.stream.Collectors;

public final class BackfillProcess extends BaritoneProcessHelper {

    public HashMap<BlockPos, IBlockState> blocksToReplace = new HashMap<>();

    public BackfillProcess(Baritone baritone) {
        super(baritone);
    }

    @Override
    public boolean isActive() {
        if (ctx.player() == null || ctx.world() == null) {
            return false;
        }
        if (!Baritone.settings().backfill.value) {
            return false;
        }
        if (Baritone.settings().allowParkour.value) {
            logDirect("Backfill cannot be used with allowParkour true");
            Baritone.settings().backfill.value = false;
            return false;
        }
        for (BlockPos pos : new ArrayList<>(blocksToReplace.keySet())) {
            if (!ctx.world().isLoaded(pos.getX(),pos.getZ()) || ctx.world().getBlockState(pos).getBlock() != Blocks.AIR) {
                blocksToReplace.remove(pos);
            }
        }
        amIBreakingABlockHMMMMMMM();
        baritone.getInputOverrideHandler().clearAllKeys();

        return !toFillIn().isEmpty();
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (!isSafeToCancel) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        baritone.getInputOverrideHandler().clearAllKeys();
        for (BlockPos toPlace : toFillIn()) {
            MovementState fake = new MovementState();
            switch (MovementHelper.attemptToPlaceABlock(fake, baritone, toPlace, false, false)) {
                case NO_OPTION:
                    continue;
                case READY_TO_PLACE:
                    baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                case ATTEMPTING:
                    // patience
                    baritone.getLookBehavior().updateTarget(fake.getTarget().getRotation().get(), true);
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                default:
                    throw new IllegalStateException();
            }
        }
        return new PathingCommand(null, PathingCommandType.DEFER); // cede to other process
    }

    private void amIBreakingABlockHMMMMMMM() {
        if (!ctx.getSelectedBlock().isPresent() || !baritone.getPathingBehavior().isPathing()) {
            return;
        }
        blocksToReplace.put(ctx.getSelectedBlock().get(), ctx.world().getBlockState(ctx.getSelectedBlock().get()));
    }

    public List<BlockPos> toFillIn() {
        return blocksToReplace
                .keySet()
                .stream()
                .filter(pos -> ctx.world().getBlockState(pos).getBlock() == Blocks.AIR)
                .filter(pos -> baritone.getInventoryBehavior().selectThrowawayForLocation(false,pos.getX(),pos.getY(),pos.getZ()) && nativeMayPlace(pos))
                .filter(pos -> !partOfCurrentMovement(pos))
                .sorted(Comparator.<BlockPos>comparingDouble(pos -> ctx.playerFeet().distanceSq(pos)).reversed())
                .collect(Collectors.toList());
    }

    private boolean nativeMayPlace(BlockPos pos){
        for(var stack:ctx.player().inventory.mainInventory)if(stack!=null&&stack.getItem() instanceof net.minecraft.item.ItemBlock
            && Baritone.settings().acceptableThrowawayItems.value.contains(stack.getItem())&&baritone.getInventoryBehavior().throwawayFilter.test(stack)){
            var block=net.minecraft.block.Block.getBlockFromItem(stack.getItem());
            if(ctx.world().nativeWorld.canPlaceEntityOnSide(block,pos.getX(),pos.getY(),pos.getZ(),false,1,null,stack))return true;
        }
        return false;
    }
    private boolean partOfCurrentMovement(BlockPos pos) {
        PathExecutor exec = baritone.getPathingBehavior().getCurrent();
        if (exec == null || exec.finished() || exec.failed()) {
            return false;
        }
        Movement movement = (Movement) exec.getPath().movements().get(exec.getPosition());
        return Arrays.asList(movement.toBreakAll()).contains(pos);
    }

    @Override
    public void onLostControl() {
        if (blocksToReplace != null && !blocksToReplace.isEmpty()) {
            blocksToReplace.clear();
        }
    }

    @Override
    public String displayName0() {
        return "Backfill";
    }

    @Override
    public boolean isTemporary() {
        return true;
    }

    @Override
    public double priority() {
        return 5;
    }
}
