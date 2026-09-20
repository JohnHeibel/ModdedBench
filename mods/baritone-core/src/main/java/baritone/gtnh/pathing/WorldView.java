/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package baritone.gtnh.pathing;

import java.util.List;

/**
 * Thread-independent immutable snapshot supplied by the Forge adapter.
 * Implementations must not read the live Minecraft world while a search is running.
 */
public interface WorldView {
    boolean isLoaded(int x, int y, int z);

    List<Move> moves(BlockPos from);
}
