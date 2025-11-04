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

    public CurrentWeightedMovingAverageFilter(double alpha) {
        clampAlpha(alpha);
    }

    /**
     * Sets the smoothing factor safely.
     * Values outside [0,1] are clamped; NaN/∞ are ignored.
     */
    public void clampAlpha(double alpha) {
        double safe = FilterUtils.sanitizeAndClamp(alpha, 0.0, 1.0);
        if (safe != alpha) {
            logger.fine(String.format("CWMA: Alpha adjusted from %.4f to %.4f", alpha, safe));
        }
        this.alpha = safe;
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
    }

    /**
     * Per-sample pass-through.
     * CWMA math is intentionally NOT implemented here to avoid double-counting
     * when the caller already provides a history buffer (e.g., via storeData).
     */

    @Override
    protected double applyFilter(double input) {
        // Pass-through to remain compatible with AbstractFilter’s contract.
        return input;
    }

    /**
     * Buffer-based CWMA:
     *  - Treats inputs[n-1] as x[n] (the "current" sample)
     *  - Computes mean of inputs[0..n-2] as mean_prev
     *  - Returns y[n] = α * x[n] + (1 - α) * mean_prev
     *
     * Bad samples (NaN/∞) are replaced with lastOutput if available, else 0.0.
     * lastOutput is updated with the final result y[n].
     */
    public double filter(double[] inputs) {
        int n = (inputs == null) ? 0 : inputs.length;

        // If only no sample exists(initial), just return the lastOutput.
        if (n == 0) return FilterUtils.sanitize(Double.NaN, lastOutput);

        // If only one sample exists, there is no prior mean; pass-through the current sample.
        if (n == 1) return (lastOutput = FilterUtils.sanitize(inputs[0], lastOutput));

        // Compute mean of previous samples (0..n-2).
        double meanPrev = FilterUtils.mean(inputs, n - 1, lastOutput);

        // Current sample x[n]
        double xN = FilterUtils.sanitize(inputs[n - 1], lastOutput);

        // CWMA output
        double y = alpha * xN + (1.0 - alpha) * meanPrev;

        // Cache last output for downstream safety substitutions
        lastOutput = y;
        return y;
    }

}
