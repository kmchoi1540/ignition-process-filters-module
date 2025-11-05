package com.kaychoi.ignition.pid.common.filter;

import com.inductiveautomation.ignition.common.expressions.ExpressionException;

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

    /** How many samples this has actually seen (<= totalSize) */
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

        double total = flatRegion; // sum of older samples, 1.0 * flatRegion
        total += (windowSize * (windowSize + 1L)) / 2.0; // sum of newer samples, 1 + 2 + ... + windowSize * flatRegion

        // Older samples: constant weight (x1.0) / total
        double olderNorm = 1.0 / total;
        for (int i = 0; i < flatRegion; i++) w[i] = olderNorm;

        // Newer samples: linearly increasing weights (x windowSize) / total
        for (int j = 0; j < windowSize; j++) w[flatRegion + j] = (j + 1) / total;
        return w; // sum(w) == 1.0
    }

    @Override
    public void updateParameters(double[] args) throws ExpressionException {
        if (args != null && args.length >= 1) {
            int newWindow = (int) args[0];
            if (newWindow <= 0) {
                throw new ExpressionException("windowSize must be positive");
            }
            int clamped = Math.min(newWindow, this.totalSize);
            if (clamped != this.windowSize) {
                this.windowSize = clamped;
                this.weights = calculateWeights(this.totalSize, this.windowSize);
                reset();
            }
        }
    }

    @Override
    protected double applyFilter(double input) {
        double safe = FilterUtils.sanitize(input, lastOutput);
        // Write to circular buffer
        buffer[index] = safe;
        index = (index + 1) % totalSize;

        if (count < totalSize) count++;

        // if only one sample → passthrough
        if (count == 1) return safe;

        int effectiveSize = count;

        // Oldest entry in the current effective buffer
        int start = (index + totalSize - effectiveSize) % totalSize;

        // --- Startup phase: not yet full ---
        if (effectiveSize < totalSize) {
            double sum = 0.0;
            double weightSum = 0.0;

            for (int i = 0; i < effectiveSize; i++) {
                int pos = start + i;
                if (pos >= totalSize) pos -= totalSize;

                double w = i + 1; // simple linear weights
                sum += buffer[pos] * w;
                weightSum += w;
            }

            return (weightSum == 0.0) ? safe : (sum / weightSum);
        }

        // --- Steady-state: buffer full, use pre-normalized weights ---
        double dot = 0.0;

        for (int i = 0; i < totalSize; i++) {
            int pos = start + i;
            if (pos >= totalSize) pos -= totalSize;
            dot += buffer[pos] * weights[i];
        }

        return dot; // weights[] already normalized (sum == 1.0)
    }

    @Override
    public void reset() {
        index = 0;
        count = 0;
        // This keeps buffer contents; they will be overwritten as new inputs arrive
    }
}
