/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package baritone.gtnh.pathing;

/** An open set for A* or a similar graph search algorithm. */
interface IOpenSet {
    void insert(PathNode node);

    boolean isEmpty();

    PathNode removeLowest();

    /** Performs the decrease-key operation after a cheaper route is found. */
    void update(PathNode node);
}
