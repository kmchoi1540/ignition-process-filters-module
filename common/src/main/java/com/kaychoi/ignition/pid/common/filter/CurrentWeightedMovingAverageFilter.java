package com.kaychoi.ignition.pid.common.filter;

/**
 * Simple exponential-like smoothing filter:
 *
 * y[k] = w * x[k] + (1 - w) * y[k-1]
 *
 * where 0 <= w <= 1.
 *
 * - If w is close to 1.0 → more responsive
 * - If w is close to 0.0 → smoother but slower
 *
 * Mode usage:
 *   mode = 1
 *   args[0] = weight
 */
public class CurrentWeightedMovingAverageFilter extends AbstractFilter {

    /** Smoothing factor between 0.0 and 1.0 */
    private double weight;

    public CurrentWeightedMovingAverageFilter(double weight) {
        setWeight(weight);
    }

    public void setWeight(double weight) {
        if (weight < 0.0 || weight > 1.0) {
            throw new IllegalArgumentException("weight must be between 0.0 and 1.0");
        }
        this.weight = weight;
    }

    @Override
    public void updateParameters(double[] args) {
        if (args != null && args.length == 1) {
            setWeight(args[0]);
        }
    }

    @Override
    protected double applyFilter(double input) {
        // First sample → pass-through
        if (lastOutput == null) {
            return input;
        }
        return weight * input + (1.0 - weight) * lastOutput;
    }
}
