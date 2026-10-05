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

package baritone.pathing.calc;

import baritone.Baritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.movement.ActionCosts;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.calc.openset.BinaryHeapOpenSet;
import baritone.pathing.movement.CalculationContext;
import baritone.utils.pathing.BetterWorldBorder;
import baritone.utils.pathing.Favoring;
import baritone.utils.pathing.MutableMoveResult;

import java.util.Optional;

/**
 * The actual A* pathfinding
 *
 * @author leijurv
 */
public final class AStarPathFinder extends AbstractNodeCostSearch {

    private final Favoring favoring;
    private final CalculationContext calcContext;

    public AStarPathFinder(int startX, int startY, int startZ, Goal goal, Favoring favoring, CalculationContext context) {
        super(startX, startY, startZ, goal, context);
        this.favoring = favoring;
        this.calcContext = context;
    }

    @Override
    protected Optional<IPath> calculate0(long primaryTimeout, long failureTimeout) {
        plannedUnderWater = baritone.pathing.movement.MovementHelper.PLANNED_UNDER_WATER.get(); // this search's thread
        try {
            return search(primaryTimeout, failureTimeout);
        } finally {
            plannedUnderWater[0] = false;
        }
    }

    private boolean[] plannedUnderWater;

    private Optional<IPath> search(long primaryTimeout, long failureTimeout) {
        startNode = getNodeAtPosition(startX, startY, startZ, BetterBlockPos.longHash(startX, startY, startZ));
        startNode.cost = 0;
        startNode.combinedCost = startNode.estimatedCostToGoal;
        // ModdedBench: breath (see breathe). A player starting under water has used 300 - air ticks of it getting here.
        startNode.submerged = baritone.pathing.movement.MovementHelper.isWater(calcContext.get(startX, startY + 1, startZ).getBlock());
        startNode.air = startNode.submerged ? calcContext.air : baritone.compat.CalculationInputs.FULL_AIR;
        // the way back out: the time under water so far, or straight up where the water above opens to air
        double up = startNode.submerged ? baritone.pathing.movement.MovementHelper.swimUpTicks(calcContext.bsi, startX, startY, startZ) : -1;
        startNode.back = !startNode.submerged ? 0 : up >= 0 ? Math.min(up, baritone.compat.CalculationInputs.FULL_AIR - calcContext.air)
                : baritone.compat.CalculationInputs.FULL_AIR - calcContext.air;
        startNode.breathed = !startNode.submerged;
        BinaryHeapOpenSet openSet = new BinaryHeapOpenSet();
        openSet.insert(startNode);
        double[] bestHeuristicSoFar = new double[COEFFICIENTS.length];//keep track of the best node by the metric of (estimatedCostToGoal + cost / COEFFICIENTS[i])
        for (int i = 0; i < bestHeuristicSoFar.length; i++) {
            bestHeuristicSoFar[i] = startNode.estimatedCostToGoal;
            bestSoFar[i] = startNode;
        }
        MutableMoveResult res = new MutableMoveResult();
        BetterWorldBorder worldBorder = calcContext.worldBorder;
        long startTime = System.currentTimeMillis();
        boolean slowPath = Baritone.settings().slowPath.value;
        if (slowPath) {
            logDebug("slowPath is on, path timeout will be " + Baritone.settings().slowPathTimeoutMS.value + "ms instead of " + primaryTimeout + "ms");
        }
        long primaryTimeoutTime = startTime + (slowPath ? Baritone.settings().slowPathTimeoutMS.value : primaryTimeout);
        long failureTimeoutTime = startTime + (slowPath ? Baritone.settings().slowPathTimeoutMS.value : failureTimeout);
        boolean failing = true;
        int numNodes = 0;
        int numMovementsConsidered = 0;
        int numEmptyChunk = 0;
        boolean isFavoring = !favoring.isEmpty();
        int timeCheckInterval = 1 << 6;
        int pathingMaxChunkBorderFetch = Baritone.settings().pathingMaxChunkBorderFetch.value; // grab all settings beforehand so that changing settings during pathing doesn't cause a crash or unpredictable behavior
        double minimumImprovement = Baritone.settings().minimumImprovementRepropagation.value ? MIN_IMPROVEMENT : 0;
        baritone.gtnh.pathing.Move[] allMoves = calcContext.moves; // ModdedBench: the walker's own and MoveRegistry's
        while (!openSet.isEmpty() && numEmptyChunk < pathingMaxChunkBorderFetch && !cancelRequested) {
            if ((numNodes & (timeCheckInterval - 1)) == 0) { // only call this once every 64 nodes (about half a millisecond)
                long now = System.currentTimeMillis(); // since nanoTime is slow on windows (takes many microseconds)
                if (now - failureTimeoutTime >= 0 || (!failing && now - primaryTimeoutTime >= 0)) {
                    break;
                }
            }
            if (slowPath) {
                try {
                    Thread.sleep(Baritone.settings().slowPathTimeDelayMS.value);
                } catch (InterruptedException ignored) {}
            }
            PathNode currentNode = openSet.removeLowest();
            mostRecentConsidered = currentNode;
            numNodes++;
            if (goal.isInGoal(currentNode.x, currentNode.y, currentNode.z) && acceptable(currentNode)) {
                logDebug("Took " + (System.currentTimeMillis() - startTime) + "ms, " + numMovementsConsidered + " movements considered");
                return Optional.of(new Path(startNode, loopFreeEnd(currentNode), numNodes, goal, calcContext));
            }
            plannedUnderWater[0] = currentNode.submerged; // ModdedBench: see MovementHelper.PLANNED_UNDER_WATER
            for (baritone.gtnh.pathing.Move moves : allMoves) {
                int newX = currentNode.x + moves.xOffset();
                int newZ = currentNode.z + moves.zOffset();
                if ((newX >> 4 != currentNode.x >> 4 || newZ >> 4 != currentNode.z >> 4) && !calcContext.isLoaded(newX, newZ)) {
                    // only need to check if the destination is a loaded chunk if it's in a different chunk than the start of the movement
                    if (!moves.dynamicXZ()) { // only increment the counter if the movement would have gone out of bounds guaranteed
                        numEmptyChunk++;
                    }
                    continue;
                }
                if (!moves.dynamicXZ() && !worldBorder.entirelyContains(newX, newZ)) {
                    continue;
                }
                if (currentNode.y + moves.yOffset() > 256 || currentNode.y + moves.yOffset() < 0) {
                    continue;
                }
                res.reset();
                moves.apply(calcContext, currentNode.x, currentNode.y, currentNode.z, res);
                numMovementsConsidered++;
                double actionCost = res.cost;
                if (actionCost >= ActionCosts.COST_INF) {
                    continue;
                }
                if (actionCost <= 0 || Double.isNaN(actionCost)) {
                    throw new IllegalStateException(moves + " calculated implausible cost " + actionCost);
                }
                // check destination after verifying it's not COST_INF -- some movements return a static IMPOSSIBLE object with COST_INF and destination being 0,0,0 to avoid allocating a new result for every failed calculation
                if (moves.dynamicXZ() && !worldBorder.entirelyContains(res.x, res.z)) { // see issue #218
                    continue;
                }
                if (!moves.dynamicXZ() && (res.x != newX || res.z != newZ)) {
                    throw new IllegalStateException(moves + " " + res.x + " " + newX + " " + res.z + " " + newZ);
                }
                if (!moves.dynamicY() && res.y != currentNode.y + moves.yOffset()) {
                    throw new IllegalStateException(moves + " " + res.y + " " + (currentNode.y + moves.yOffset()));
                }
                if (!calcContext.positionAllowed.test(new baritone.compat.BlockPos(res.x,res.y,res.z))) {
                    continue;
                }
                // ModdedBench: an edge that snagged this job is not planned again, or the re-plan hands back the same path
                if (!calcContext.snags.allows(currentNode.x, currentNode.y, currentNode.z, res.x, res.y, res.z)) {
                    continue;
                }
                // ModdedBench: a current slows a swim across or against it and speeds one along it
                double[] flow = flowAround(currentNode.x, currentNode.y, currentNode.z);
                if (flow != null && (res.x != currentNode.x || res.z != currentNode.z)) {
                    actionCost *= baritone.pathing.movement.MovementHelper.swimFactor(flow, res.x - currentNode.x, res.z - currentNode.z);
                    if (actionCost >= ActionCosts.COST_INF) {
                        continue;
                    }
                }
                // ModdedBench: with the head under water a move costs air as well as time (Settings.submergedPenalty and
                // breathSafety); no route runs out of air before the head is out of water.
                boolean submerged = headUnderWater(currentNode, res.x, res.y + 1, res.z);
                double edgeTicks = actionCost, airLeft = airAfter(currentNode, edgeTicks, submerged);
                if (airLeft < 0) {
                    breathPruned = true;
                    continue;
                }
                if (submerged) {
                    actionCost += calcContext.submergedPenalty;
                }
                if (currentTowardsHarm(res.x, res.y, res.z)) {
                    actionCost += calcContext.currentHazardPenalty;
                }
                long hashCode = BetterBlockPos.longHash(res.x, res.y, res.z);
                if (isFavoring) {
                    // see issue #18
                    actionCost *= favoring.calculate(hashCode);
                }
                double tentativeCost = currentNode.cost + actionCost;
                if (submerged && dominated(hashCode, airLeft, currentNode.back + swimBack(currentNode, res.x, res.y, res.z, edgeTicks), tentativeCost)) {
                    continue;
                }
                PathNode neighbor = submerged ? underwaterNode(res.x, res.y, res.z, hashCode, airLeft) : getNodeAtPosition(res.x, res.y, res.z, hashCode);
                if (neighbor.cost - tentativeCost > minimumImprovement) {
                    breathe(neighbor, currentNode, edgeTicks, submerged);
                    neighbor.previous = currentNode;
                    neighbor.cost = tentativeCost;
                    neighbor.combinedCost = tentativeCost + neighbor.estimatedCostToGoal;
                    if (neighbor.isOpen()) {
                        openSet.update(neighbor);
                    } else {
                        openSet.insert(neighbor);//dont double count, dont insert into open set if it's already there
                    }
                    for (int i = 0; canGetOut(neighbor) && i < COEFFICIENTS.length; i++) { // ModdedBench: no partial path ends where air would run out
                        double heuristic = neighbor.estimatedCostToGoal + neighbor.cost / COEFFICIENTS[i];
                        if (bestHeuristicSoFar[i] - heuristic > minimumImprovement) {
                            bestHeuristicSoFar[i] = heuristic;
                            bestSoFar[i] = neighbor;
                            if (failing && getDistFromStartSq(neighbor) > MIN_DIST_PATH * MIN_DIST_PATH) {
                                failing = false;
                            }
                        }
                    }
                }
            }
        }
        if (cancelRequested) {
            return Optional.empty();
        }
        // ModdedBench: record why the loop ended; one node means not one movement left the start
        double best = 0;
        for (PathNode node : bestSoFar) {
            if (node != null) {
                best = Math.max(best, Math.sqrt(getDistFromStartSq(node)));
            }
        }
        searchStats = java.util.Map.of("why", openSet.isEmpty() ? "exhausted" : numEmptyChunk >= pathingMaxChunkBorderFetch ? "unloaded_chunks" : "timeout",
                "nodes", numNodes, "movements", numMovementsConsidered, "bestDistance", Math.round(best * 10) / 10.0);
        Optional<IPath> result = bestSoFar(true, numNodes);
        if (result.isEmpty() && breathPruned) {
            searchStats = new java.util.HashMap<>(searchStats);
            searchStats.put("breath", "routes pruned: not enough air to swim them");
        }
        if (result.isEmpty() && !calcContext.protectedMet.isEmpty()) { // ModdedBench: a search that found nothing names the regions that refused it an edit
            searchStats = new java.util.HashMap<>(searchStats);
            searchStats.put("protectedRegions", calcContext.protectedMet.stream().sorted().toList());
        }
        if (result.isPresent()) {
            logDebug("Took " + (System.currentTimeMillis() - startTime) + "ms, " + numMovementsConsidered + " movements considered");
        }
        return result;
    }

    /**
     * ModdedBench: air left after this edge, or 1 when neither end is under water. Moves are charged in estimated ticks
     * times breathSafety, the air the game takes per tick under water; a route still under water since the start (the
     * player was under water already) is charged the plain estimate, as getting out is the point.
     */
    private double airAfter(PathNode from, double edgeTicks, boolean submerged) {
        if (!submerged && !from.submerged) {
            return 1;
        }
        return from.air - edgeTicks * (from.breathed ? calcContext.breathSafety : 1);
    }

    /** ModdedBench: sets to's breath for the edge from from; with the head out of water the air refills, as in 1.7.10. */
    private void breathe(PathNode to, PathNode from, double edgeTicks, boolean submerged) {
        to.edgeTicks = edgeTicks;
        to.submerged = submerged;
        to.air = submerged ? airAfter(from, edgeTicks, true) : baritone.compat.CalculationInputs.FULL_AIR;
        to.back = submerged ? from.back + swimBack(from, to, edgeTicks) : 0;
        to.breathed = from.breathed || !submerged;
    }

    private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<double[]> flows = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    private static final double[] STILL = new double[0];

    /**
     * ModdedBench: the way the water around a player standing at (x,y,z) pushes them (feet and head cells, as the game sums
     * the body's cells), a horizontal unit vector, or null. Asked once a cell per search.
     */
    private double[] flowAround(int x, int y, int z) {
        if (!baritone.pathing.movement.MovementHelper.isWater(calcContext.get(x, y, z).getBlock())
                && !baritone.pathing.movement.MovementHelper.isWater(calcContext.get(x, y + 1, z).getBlock())) {
            return null;
        }
        long key = BetterBlockPos.longHash(x, y, z);
        double[] flow = flows.get(key);
        if (flow == null) {
            double[] feet = baritone.pathing.movement.MovementHelper.flowAt(calcContext.bsi, x, y, z);
            double[] head = baritone.pathing.movement.MovementHelper.flowAt(calcContext.bsi, x, y + 1, z);
            double fx = (feet == null ? 0 : feet[0]) + (head == null ? 0 : head[0]), fz = (feet == null ? 0 : feet[1]) + (head == null ? 0 : head[1]);
            double length = Math.sqrt(fx * fx + fz * fz);
            flow = length < 1e-6 ? STILL : new double[]{fx / length, fz / length};
            flows.put(key, flow);
        }
        return flow == STILL ? null : flow;
    }

    /** ModdedBench: whether the current at (x,y,z) pushes towards a harmful cell next to it (Settings.currentHazardPenalty). */
    private boolean currentTowardsHarm(int x, int y, int z) {
        double[] flow = flowAround(x, y, z);
        if (flow == null) {
            return false;
        }
        int sx = Math.abs(flow[0]) > 0.3 ? (int) Math.signum(flow[0]) : 0, sz = Math.abs(flow[1]) > 0.3 ? (int) Math.signum(flow[1]) : 0;
        return sx != 0 && harmful(x + sx, y, z) || sz != 0 && harmful(x, y, z + sz) || sx != 0 && sz != 0 && harmful(x + sx, y, z + sz);
    }

    private boolean harmful(int x, int y, int z) {
        for (int dy = 0; dy <= 1; dy++) {
            baritone.compat.IBlockState state = calcContext.get(x, y + dy, z);
            net.minecraft.block.material.Material m = state.getBlock().getMaterial();
            if (m.isLiquid() && m != net.minecraft.block.material.Material.water || baritone.pathing.movement.MovementHelper.hazard(state)) {
                return true;
            }
        }
        return false;
    }

    /** ModdedBench: the ticks to swim this edge back: its distance, not the digging that opened it, at most the edge's own. */
    private static double swimBack(PathNode from, PathNode to, double edgeTicks) {
        return swimBack(from, to.x, to.y, to.z, edgeTicks);
    }

    private static double swimBack(PathNode from, int x, int y, int z, double edgeTicks) {
        int dx = x - from.x, dy = y - from.y, dz = z - from.z;
        return Math.min(edgeTicks, ActionCosts.WALK_ONE_IN_WATER_COST * Math.sqrt(dx * dx + dy * dy + dz * dz));
    }

    /**
     * ModdedBench: whether this cell was already reached under water with no less air, no further to swim back and no
     * more cost: such a state does everything this one could, so this one is not searched. Without it every cell of a
     * lake was searched once per air band.
     */
    private boolean dominated(long hashCode, double air, double back, double cost) {
        for (it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<PathNode> band : underwater) {
            PathNode n = band == null ? null : band.get(hashCode);
            if (n != null && n.air >= air && n.back <= back && n.cost <= cost) {
                return true;
            }
        }
        return false;
    }

    /**
     * ModdedBench: whether a move from from ends with the head in (x,y,z) under water. A head cell the move digs out is
     * no breath: water above or beside it flows in, and digging from under water it is the same water.
     */
    private boolean headUnderWater(PathNode from, int x, int y, int z) {
        baritone.compat.IBlockState head = calcContext.get(x, y, z);
        if (baritone.pathing.movement.MovementHelper.isWater(head.getBlock())) {
            return true;
        }
        if (baritone.pathing.movement.MovementHelper.canWalkThrough(calcContext, x, y, z, head)) {
            return false;
        }
        return from.submerged || wet(x, y + 1, z) || wet(x + 1, y, z) || wet(x - 1, y, z) || wet(x, y, z + 1) || wet(x, y, z - 1);
    }

    private boolean wet(int x, int y, int z) {
        return baritone.pathing.movement.MovementHelper.isWater(calcContext.get(x, y, z).getBlock());
    }

    /** ModdedBench: a route that ends under water keeps the air to get back out the way it came. */
    private boolean canGetOut(PathNode n) {
        return !n.submerged || n.air >= n.back * calcContext.breathSafety;
    }

    /**
     * ModdedBench: replays the breath along the route that previous pointers give now. A node's breath was set from the
     * route its parent had then, and a parent whose cost later improved may now be reached another way.
     */
    @Override
    protected boolean acceptable(PathNode end) {
        java.util.ArrayDeque<PathNode> route = new java.util.ArrayDeque<>();
        for (PathNode n = end; n != null && n != startNode; n = n.previous) {
            route.push(n);
        }
        PathNode at = startNode;
        for (PathNode n : route) {
            if (airAfter(at, n.edgeTicks, n.submerged) < 0) {
                breathPruned = true;
                return false;
            }
            PathNode step = new PathNode(n.x, n.y, n.z, goal);
            breathe(step, at, n.edgeTicks, n.submerged);
            at = step;
        }
        if (!canGetOut(at)) {
            breathPruned = true;
            return false;
        }
        return true;
    }

    private boolean breathPruned;

    /**
     * ModdedBench: a route may come back through a cell after a breath (up into an air pocket and down again), and a path
     * may not visit a cell twice: the executor finds the player by cell, and could skip the breath. Such a path stops at
     * that breath, the last cell with the head out of water before the repeat; the next segment starts from it.
     */
    @Override
    protected PathNode loopFreeEnd(PathNode end) {
        java.util.ArrayList<PathNode> route = new java.util.ArrayList<>();
        for (PathNode n = end; n != null; n = n.previous) {
            route.add(n);
        }
        java.util.Collections.reverse(route);
        it.unimi.dsi.fastutil.longs.LongOpenHashSet seen = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        for (int i = 0; i < route.size(); i++) {
            PathNode n = route.get(i);
            if (!seen.add(BetterBlockPos.longHash(n.x, n.y, n.z))) {
                for (int k = i - 1; k > 0; k--) {
                    if (!route.get(k).submerged) {
                        return route.get(k);
                    }
                }
                return route.get(i - 1);
            }
        }
        return end;
    }

    /**
     * ModdedBench: under water a cell is a node per band of air left, not one node. Keyed by position alone, a cheap way
     * there that never breathed would shut out a dearer one past an air pocket that arrives with the air to go on.
     */
    @SuppressWarnings("unchecked")
    private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<PathNode>[] underwater = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap[6];

    private PathNode underwaterNode(int x, int y, int z, long hashCode, double air) {
        int band = Math.max(0, Math.min(underwater.length - 1, (int) (air / 50)));
        if (underwater[band] == null) {
            underwater[band] = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
        }
        PathNode node = underwater[band].get(hashCode);
        if (node == null) {
            node = new PathNode(x, y, z, goal);
            underwater[band].put(hashCode, node);
        }
        return node;
    }
}
