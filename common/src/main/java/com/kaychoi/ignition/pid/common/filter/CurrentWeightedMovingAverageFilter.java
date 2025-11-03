package com.kaychoi.ignition.pid.common.filter;

/**
 * Current Weighted Moving Average (CWMA)
 *
 * Formula:
 *   y[n] = α * x[n] + (1 - α) * mean_prev
 *
 * where:
 *   - mean_prev = average of all previous input samples (x[0] ... x[n-1])
 *   - α ∈ [0, 1]
 *
 * Behavior:
 *   - If α → 1.0, the filter reacts quickly (less smoothing)
 *   - If α → 0.0, the filter is smoother (slower response)
 *
 * Mode usage in CustomFilterFunction:
 *   mode = 1
 *   args[0] = α (weight)
 */
public class CurrentWeightedMovingAverageFilter extends AbstractFilter {

    /** Smoothing weight (0.0 ~ 1.0) */
    private double alpha = 0.5;

    /** Accumulated sum and count for previous samples only (for mean_prev) */
    private double sumPrev = 0.0;
    private long countPrev = 0L;

    public CurrentWeightedMovingAverageFilter(double alpha) {
        clampAlpha(alpha);
    }

    /**
     * Sets the smoothing factor safely.
     * Values outside [0,1] are clamped; NaN/∞ are ignored.
     */
    public void clampAlpha(double alpha) {
        if (Double.isNaN(alpha) || Double.isInfinite(alpha)) {
            logger.warning("CWMA: Ignoring invalid alpha (NaN or ∞)");
            return;
        }
        if (alpha < 0.0) alpha = 0.0;
        if (alpha > 1.0) alpha = 1.0;
        this.alpha = alpha;
    }

    /**
     * Updates filter parameters when CustomFilterFunction changes args.
     */
    @Override
    public void updateParameters(double[] args) {
        if (args != null && args.length >= 1) {
            clampAlpha(args[0]);
        }
    }

    /**
     * Resets the filter’s internal state.
     */
    @Override
    public void reset() {
        super.reset();
        sumPrev = 0.0;
        countPrev = 0L;
    }

    /**
     * Applies CWMA filtering to the given input sample.
     */
    @Override
    protected double applyFilter(double input) {
        // No previous samples → pass-through
        if (countPrev == 0L) {
            accumulatePrev(input);
            return input;
        }

        // Compute mean of previous samples
        double meanPrev = sumPrev / (double) countPrev;

        // CWMA formula: y = α*x + (1−α)*mean_prev
        double y = alpha * input + (1.0 - alpha) * meanPrev;

        // Update statistics for next iteration
        accumulatePrev(input);

        return y;
    }

    /**
     * Updates the running total of previous samples.
     * NaN or ∞ inputs are replaced by the last valid output.
     */
    private void accumulatePrev(double x) {
        if (Double.isNaN(x) || Double.isInfinite(x)) {
            double safe = (lastOutput != null) ? lastOutput : 0.0;
            x = safe;
        }
        sumPrev += x;
        countPrev++;
    }
}
