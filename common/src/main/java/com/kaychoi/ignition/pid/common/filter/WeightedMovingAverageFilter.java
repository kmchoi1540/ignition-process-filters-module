package com.kaychoi.ignition.pid.common.filter;

/**
 * Weighted Moving Average (WMA) filter with:
 * - Circular buffer
 * - Linearly increasing weights for the most recent samples
 * - Support for partially-filled buffer (startup phase)
 *
 * Constructor:
 *   WeightedMovingAverageFilter(int windowSize, int totalSize)
 *
 * - totalSize is typically the current input-length given by the expression
 * - windowSize <= totalSize
 *
 * Mode usage:
 *   mode = 2
 *   args[0] = windowSize
 */
public class WeightedMovingAverageFilter extends AbstractFilter {

    /** Sliding window length (emphasis region) */
    private int windowSize;

    /** Physical buffer length (circular buffer capacity) */
    private final int totalSize;

    /** Precomputed normalized weights for the fully-filled case */
    private double[] weights;

    /** Circular buffer to hold recent inputs */
    private final double[] buffer;

    /** Next index to write to */
    private int index = 0;

    /** How many samples we have actually seen (<= totalSize) */
    private int count = 0;

    public WeightedMovingAverageFilter(int windowSize, int totalSize) {
        if (windowSize <= 0 || totalSize <= 0) {
            throw new IllegalArgumentException("windowSize and totalSize must be positive");
        }
        this.totalSize = totalSize;
        this.windowSize = Math.min(windowSize, totalSize);
        this.weights = calculateWeights(this.totalSize, this.windowSize);
        this.buffer = new double[this.totalSize];
    }

    /**
     * Precompute weights:
     * - Older part (before window) = 1.0
     * - Newer part (window) = 1..windowSize
     * - Then normalize once
     */
    private double[] calculateWeights(int totalSize, int windowSize) {
        double[] w = new double[totalSize];
        int flatRegion = totalSize - windowSize;
        double total = 0.0;

        // Older samples: constant weight
        for (int i = 0; i < flatRegion; i++) {
            w[i] = 1.0;
            total += 1.0;
        }

        // Newer samples: linearly increasing weights
        for (int i = 0; i < windowSize; i++) {
            double val = i + 1;
            w[flatRegion + i] = val;
            total += val;
        }

        // Normalize
        for (int i = 0; i < totalSize; i++) {
            w[i] /= total;
        }
        return w;
    }

    @Override
    public void updateParameters(double[] args) {
        if (args != null && args.length == 1) {
            int newWindow = (int) args[0];
            if (newWindow <= 0) {
                throw new IllegalArgumentException("windowSize must be positive");
            }
            this.windowSize = Math.min(newWindow, this.totalSize);
            this.weights = calculateWeights(this.totalSize, this.windowSize);
            reset();
        }
    }

    @Override
    protected double applyFilter(double input) {
        // Write to circular buffer
        buffer[index] = input;
        index = (index + 1) % totalSize;

        if (count < totalSize) count++;
        int effectiveSize = count;

        // First value → just return
        if (effectiveSize == 1) {
            return input;
        }

        double sum = 0.0;
        double weightSum = 0.0;

        // Oldest entry in the current effective buffer
        int start = (index + totalSize - effectiveSize) % totalSize;

        for (int i = 0; i < effectiveSize; i++) {
            int pos = start + i;
            if (pos >= totalSize) pos -= totalSize;

            // Before buffer is full, use simple increasing weights
            double w = (effectiveSize < totalSize) ? (i + 1) : weights[i];

            sum += buffer[pos] * w;
            weightSum += w;
        }

        return (weightSum == 0.0) ? input : (sum / weightSum);
    }

    @Override
    public void reset() {
        index = 0;
        count = 0;
        // we keep buffer contents; they will be overwritten as new inputs arrive
    }
}
