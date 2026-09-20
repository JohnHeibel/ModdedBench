/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package baritone.gtnh.pathing;

import java.util.Arrays;

/** A binary heap implementation of the upstream Baritone A* open set. */
final class BinaryHeapOpenSet implements IOpenSet {
    private static final int INITIAL_CAPACITY = 1024;
    private PathNode[] array;
    private int size;

    BinaryHeapOpenSet() {
        this(INITIAL_CAPACITY);
    }

    BinaryHeapOpenSet(int size) {
        this.size = 0;
        this.array = new PathNode[size];
    }

    int size() {
        return size;
    }

    @Override
    public void insert(PathNode value) {
        if (size >= array.length - 1) {
            array = Arrays.copyOf(array, array.length << 1);
        }
        size++;
        value.heapPosition = size;
        array[size] = value;
        update(value);
    }

    @Override
    public void update(PathNode value) {
        int index = value.heapPosition;
        int parentIndex = index >>> 1;
        double cost = value.combinedCost;
        PathNode parentNode = array[parentIndex];
        while (index > 1 && parentNode.combinedCost > cost) {
            array[index] = parentNode;
            array[parentIndex] = value;
            value.heapPosition = parentIndex;
            parentNode.heapPosition = index;
            index = parentIndex;
            parentIndex = index >>> 1;
            parentNode = array[parentIndex];
        }
    }

    @Override
    public boolean isEmpty() {
        return size == 0;
    }

    @Override
    public PathNode removeLowest() {
        if (size == 0) {
            throw new IllegalStateException();
        }
        PathNode result = array[1];
        PathNode value = array[size];
        array[1] = value;
        value.heapPosition = 1;
        array[size] = null;
        size--;
        result.heapPosition = -1;
        if (size < 2) {
            return result;
        }
        int index = 1;
        int smallerChild = 2;
        double cost = value.combinedCost;
        do {
            PathNode child = array[smallerChild];
            double childCost = child.combinedCost;
            if (smallerChild < size) {
                PathNode rightChild = array[smallerChild + 1];
                double rightCost = rightChild.combinedCost;
                if (childCost > rightCost) {
                    smallerChild++;
                    childCost = rightCost;
                    child = rightChild;
                }
            }
            if (cost <= childCost) {
                break;
            }
            array[index] = child;
            array[smallerChild] = value;
            value.heapPosition = smallerChild;
            child.heapPosition = index;
            index = smallerChild;
        } while ((smallerChild <<= 1) <= size);
        return result;
    }
}
