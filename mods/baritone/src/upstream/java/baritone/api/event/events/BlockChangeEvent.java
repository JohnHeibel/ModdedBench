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

package baritone.api.event.events;

import baritone.api.utils.Pair;
import baritone.compat.IBlockState;
import baritone.compat.BlockPos;
import baritone.compat.ChunkPos;

import java.util.List;

/**
 * @author Brady
 */
public final class BlockChangeEvent {

    private final ChunkPos chunk;
    private final List<Pair<BlockPos, IBlockState>> blocks;

    public BlockChangeEvent(ChunkPos pos, List<Pair<BlockPos, IBlockState>> blocks) {
        this.chunk = pos;
        this.blocks = blocks;
    }

    public ChunkPos getChunkPos() {
        return this.chunk;
    }

    public List<Pair<BlockPos, IBlockState>> getBlocks() {
        return this.blocks;
    }
}
