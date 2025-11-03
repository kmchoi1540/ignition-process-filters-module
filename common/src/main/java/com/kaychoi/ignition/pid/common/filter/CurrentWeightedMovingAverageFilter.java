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
        if (inputs == null || inputs.length == 0) {
            return (lastOutput != null) ? lastOutput : 0.0;
        }

        // If only one sample exists, there is no prior mean; pass-through the current sample.
        if (inputs.length == 1) {
            double x = sanitize(inputs[0]);
            lastOutput = x;
            return x;
        }

        int n = inputs.length;
        // Compute mean of previous samples (0..n-2).
        double sumPrev = 0.0;
        for (int i = 0; i < n - 1; i++) {
            sumPrev += sanitize(inputs[i]);
        }
        double meanPrev = sumPrev / (n - 1);

        // Current sample x[n]
        double xN = sanitize(inputs[n - 1]);

        // CWMA output
        double y = alpha * xN + (1.0 - alpha) * meanPrev;
        // Cache last output for downstream safety substitutions
        lastOutput = y;
        return y;
    }

    /**
     * Replaces NaN/∞ with a safe fallback (lastOutput if available, otherwise 0.0).
     */
    private double sanitize(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return (lastOutput != null) ? lastOutput : 0.0;
        }
        return v;
    }
}
