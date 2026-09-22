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
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.process.IFarmProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.RayTraceUtils;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.gtnh.FarmPlan;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.block.*;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.item.ItemStack;
import baritone.compat.EnumFacing;
import baritone.compat.BlockPos;
import baritone.compat.RayTraceResult;
import baritone.compat.Vec3d;
import baritone.compat.Registry;

import java.util.*;
import java.util.function.Predicate;

/** ModdedBench: what to harvest, plant, fertilize and collect is a FarmPlan (the caller's selectors, defaults shown), and
 *  ripeness and seeds are the game's answers; upstream's vanilla crop enum, item lists and chunk scan are gone. */
public final class FarmProcess extends BaritoneProcessHelper implements IFarmProcess {

    private boolean active;
    private FarmPlan plan;
    private int range;
    private BlockPos center;

    public FarmProcess(Baritone baritone) {
        super(baritone);
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public void farm(int range, BlockPos pos) {
        throw new UnsupportedOperationException("farm needs a FarmPlan: use farm(range, center, plan)");
    }

    public void farm(int range, BlockPos pos, FarmPlan plan) {
        center = pos == null ? baritone.getPlayerContext().playerFeet() : pos;
        this.range = range;
        this.plan = plan;
        active = true;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (!plan.tick()) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        var w = ctx.world().nativeWorld;
        List<BlockPos> toBreak = new ArrayList<>();
        List<BlockPos> openSoil = new ArrayList<>();
        List<BlockPos> bonemealable = new ArrayList<>();
        int growing = 0;
        boolean[] matched = new boolean[plan.crops()];
        for (BlockPos pos : plan.locations()) {
            //check if the target block is out of range.
            if (range != 0 && Math.sqrt(pos.distanceSq(center)) > range) {
                continue;
            }
            if (plan.openSoil(w, pos)) {
                if (Baritone.settings().replantCrops.value) {
                    openSoil.add(pos);
                }
                continue;
            }
            int crop = plan.crop(w, pos);
            if (crop < 0) {
                continue;
            }
            matched[crop] = true;
            if (plan.ready(w, pos, crop)) {
                toBreak.add(pos);
                continue;
            }
            growing++;
            Block block = w.getBlock(pos.getX(), pos.getY(), pos.getZ());
            if (block instanceof IGrowable ig && ig.func_149851_a(w, pos.getX(), pos.getY(), pos.getZ(), true) && ig.func_149852_a(w, w.rand, pos.getX(), pos.getY(), pos.getZ())) {
                bonemealable.add(pos);
            }
        }
        List<BlockPos> unseeded = new ArrayList<>();
        Set<String> unseededSoils = new TreeSet<>();
        for (BlockPos pos : openSoil) {
            if (!baritone.getInventoryBehavior().throwaway(false, seedFor(pos))) {
                unseeded.add(pos);
                unseededSoils.add(Registry.name(w.getBlock(pos.getX(), pos.getY(), pos.getZ())));
            }
        }
        List<Entity> drops = new ArrayList<>();
        for (Object entity : ctx.world().loadedEntityList) {
            if (entity instanceof EntityItem ei && ei.onGround && plan.collect(ei.getEntityItem()) && plan.inBounds(new BlockPos(ei.posX, ei.boundingBox.minY, ei.posZ))) {
                drops.add(ei);
            }
        }
        Map<String, Object> seen = new LinkedHashMap<>();
        seen.put("ready", toBreak.size());
        seen.put("growing", growing);
        seen.put("fertilizable", bonemealable.size());
        seen.put("openSoil", openSoil.size());
        // Open soil the inventory has no seed for, by the seed rule in force: the part of the farm this job cannot work.
        seen.put("openSoilWithoutSeed", unseeded.size());
        seen.put("soilsWithoutSeed", List.copyOf(unseededSoils));
        seen.put("drops", drops.size());
        List<Integer> unmatched = new ArrayList<>();
        for (int i = 0; i < matched.length; i++) if (!matched[i]) unmatched.add(i);
        seen.put("cropSelectorsMatchingNothing", unmatched);
        plan.seen = seen;

        baritone.getInputOverrideHandler().clearAllKeys();
        for (BlockPos pos : toBreak) {
            Optional<Rotation> rot = RotationUtils.reachable(ctx, pos);
            if (rot.isPresent() && isSafeToCancel) {
                baritone.getLookBehavior().updateTarget(rot.get(), true);
                MovementHelper.switchToBestToolFor(ctx, ctx.world().getBlockState(pos));
                if (ctx.isLookingAt(pos)) {
                    baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }
        for (BlockPos pos : openSoil) {
            if (unseeded.contains(pos)) {
                continue;
            }
            Optional<Rotation> rot = RotationUtils.reachableOffset(ctx, pos, new Vec3d(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5), ctx.playerController().getBlockReachDistance(), false);
            if (rot.isPresent() && isSafeToCancel && baritone.getInventoryBehavior().throwaway(true, seedFor(pos))) {
                RayTraceResult result = RayTraceUtils.rayTraceTowards(ctx.player(), rot.get(), ctx.playerController().getBlockReachDistance());
                if (result != null && result.typeOfHit == RayTraceResult.Type.BLOCK && result.sideHit == EnumFacing.UP) {
                    baritone.getLookBehavior().updateTarget(rot.get(), true);
                    if (ctx.isLookingAt(pos)) {
                        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                    }
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
            }
        }
        for (BlockPos pos : bonemealable) {
            Optional<Rotation> rot = RotationUtils.reachable(ctx, pos);
            if (rot.isPresent() && isSafeToCancel && baritone.getInventoryBehavior().throwaway(true, plan::fertilizer)) {
                baritone.getLookBehavior().updateTarget(rot.get(), true);
                if (ctx.isLookingAt(pos)) {
                    baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }

        if (calcFailed) {
            logDirect("Farm failed");
            if (Baritone.settings().notificationOnFarmFail.value) {
                logNotification("Farm failed", true);
            }
            onLostControl();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        List<Goal> goalz = new ArrayList<>();
        for (BlockPos pos : toBreak) {
            goalz.add(new BuilderProcess.GoalBreak(pos));
        }
        for (BlockPos pos : openSoil) {
            if (!unseeded.contains(pos)) {
                goalz.add(new GoalBlock(pos.up()));
            }
        }
        if (baritone.getInventoryBehavior().throwaway(false, plan::fertilizer)) {
            for (BlockPos pos : bonemealable) {
                goalz.add(new GoalBlock(pos));
            }
        }
        for (Entity entity : drops) {
            // +0.1 because of farmland's 0.9375 dummy height lol
            goalz.add(new GoalBlock(new BlockPos(entity.posX, entity.boundingBox.minY + 0.1, entity.posZ)));
        }
        return new PathingCommand(new GoalComposite(goalz.toArray(new Goal[0])), PathingCommandType.SET_GOAL_AND_PATH);
    }

    private Predicate<ItemStack> seedFor(BlockPos soil) {
        var w = ctx.world().nativeWorld;
        return stack -> plan.seed(stack, w, soil);
    }

    @Override
    public void onLostControl() {
        active = false;
    }

    @Override
    public String displayName0() {
        return "Farming";
    }
}
