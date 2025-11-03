package com.kaychoi.ignition.pid.common;

import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * Simple in-memory buffer for storing the most recent numeric inputs.
 * - Keeps newest at the tail (last index)
 * - Enforces max size strictly (never exceeds)
 * - Thread-safe option; when false, caller must ensure single-threaded access
 */
public class StoreData implements Iterable<Double> {

    private final Deque<Double> inputBuffer;
    private volatile int maxSize = 1;
    private final boolean threadSafe;

    public StoreData(boolean threadSafe) {
        this.inputBuffer = new ArrayDeque<>();
        this.threadSafe = threadSafe;
    }

    /**
     * Add a new input and return a defensive snapshot.
     * Rules:
     * - If (enable == false) or (size <= 0): clear and keep only current input (size forced to 1)
     * - Else: set/adjust max size, drop oldest until capacity-1, then append
     */
    public List<Double> addInput(boolean enable, int size, double input) {
        if (threadSafe) {
            synchronized (this) {
                return addInputInternal(enable, size, input);
            }
        } else {
            // NOTE: If this instance is shared across threads, set threadSafe=true.
            return addInputInternal(enable, size, input);
        }
    }

    private List<Double> addInputInternal(boolean enable, int size, double input) {
        int targetSize = (enable && size > 0) ? size : 1;

        // Update capacity if changed
        if (targetSize != maxSize) {
            maxSize = targetSize;
            trimToMaxSizeUnlocked();
        }

        if (!enable || size <= 0) {
            inputBuffer.clear();
            inputBuffer.addLast(input);
            return snapshotUnlocked();
        }

        // Ensure there is room for the new element so that final size <= maxSize
        // After this loop, buffer.size() <= maxSize - 1
        while (inputBuffer.size() >= maxSize) {
            inputBuffer.pollFirst(); // drop oldest
        }

        inputBuffer.addLast(input);
        return snapshotUnlocked();
    }

    private void trimToMaxSizeUnlocked() {
        while (inputBuffer.size() > maxSize) {
            inputBuffer.pollFirst();
        }
    }

    private List<Double> snapshotUnlocked() {
        return new ArrayList<>(inputBuffer);
    }

    public double sum() {
        if (threadSafe) {
            synchronized (this) {
                return sumUnlocked();
            }
        } else {
            return sumUnlocked();
        }
    }

    private double sumUnlocked() {
        double total = 0.0;
        for (double v : inputBuffer) total += v;
        return total;
    }

    public Double peekLast() {
        if (threadSafe) {
            synchronized (this) {
                return inputBuffer.peekLast();
            }
        } else {
            return inputBuffer.peekLast();
        }
    }

    @NotNull
    @Override
    public Iterator<Double> iterator() {
        if (threadSafe) {
            synchronized (this) {
                return new ArrayList<>(inputBuffer).iterator();
            }
        } else {
            // Return an iterator over a defensive snapshot to avoid CME/races
            return new ArrayList<>(inputBuffer).iterator();
        }
    }

    public int size() {
        if (threadSafe) {
            synchronized (this) {
                return inputBuffer.size();
            }
        } else {
            return inputBuffer.size();
        }
    }

    public void clear() {
        if (threadSafe) {
            synchronized (this) {
                inputBuffer.clear();
                maxSize = 1;
            }
        } else {
            inputBuffer.clear();
            maxSize = 1;
        }
    }

    public int getMaxSize() {
        return maxSize;
    }
}
