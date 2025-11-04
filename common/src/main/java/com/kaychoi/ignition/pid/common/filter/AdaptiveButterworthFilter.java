package com.kaychoi.ignition.pid.common.filter;

/**
 * Adaptive Butterworth IIR Filter (2nd order, low-pass)
 *
 * - Adapts cutoff frequency (fc) by input statistics.
 * - Supports both per-sample (applyFilter) and buffer-based (filter(double[])) paths.
 * - Adds short-window padding for len < 3 to stabilize early calls.
 *
 * Modes:
 *  - Manual:   new AdaptiveButterworthFilter(fs, fc, null or <=0)
 *  - Adaptive: new AdaptiveButterworthFilter(fs, baseFc, gain>0)
 *
 * Args:
 *  - fs : Sampling rate (Hz) > 0
 *  - fc : Base cutoff frequency (Hz) > 0 and < fs/2
 *  - adaptGain : Gain factor for fc tuning (null or <= 0 → manual)
 *
 * Notes:
 *  - If adaptGain ≥ 1 → clamped to 1.0 by default.
 */
public class AdaptiveButterworthFilter extends ButterworthIIRFilter {

    private double fs;
    private double baseFc;
    private double adaptGain;   // 0.0 → manual (no adaptation)
    private boolean autoEnabled;

    public AdaptiveButterworthFilter(double fs, double baseFc, Double adaptGain) {
        super(fs, baseFc); // Initialize as fixed Butterworth first
        this.fs = (fs > 0.0) ? fs : 1.0;
        this.baseFc = (baseFc > 0.0) ? baseFc : 0.1;

        if (adaptGain == null || adaptGain <= 0.0) {
            this.adaptGain = 0.0;
            this.autoEnabled = false;
        } else {
            this.adaptGain = Math.min(adaptGain, 1.0);
            this.autoEnabled = true;
        }
    }

    /**
     * Per-sample adaptive filtering:
     *  - Uses a local change proxy to adjust fc on the fly.
     *  - Keeps parent filter state (x[], y[]) and refreshes coefficients.
     */
    @Override
    protected double applyFilter(double input) {
        double x = FilterUtils.sanitize(input, lastOutput);

        if (autoEnabled) {
            // Local change proxy: |x - lastOutput|
            double change = (lastOutput != null) ? Math.abs(x - lastOutput) : 0.0;
            double fcDynamic = baseFc + adaptGain * change;

            // Stability clamps
            if (fs <= 0) fs = 1.0;
            double nyquist = fs / 2.0;
            if (fcDynamic < 1e-6) fcDynamic = 1e-6;
            if (fcDynamic >= nyquist) fcDynamic = nyquist * 0.99;

            // Recompute coefficients in place
            calculateCoefficients(fs, fcDynamic);
        }
        // Call parent math directly (no wrapper), AbstractFilter.filter() will set lastOutput for us.
        return super.applyFilter(x);
    }

    /**
     * Buffer-based adaptive filtering with short-window padding.
     *  - len==1 → [xN, xN, xN]
     *  - len==2 → [xN-1, xN-1, xN]
     *  - len>=3 → normal adaptive over the full buffer
     */
    public double filter(double[] inputs) {
        if (inputs == null || inputs.length == 0)
            return (lastOutput != null) ? lastOutput : 0.0;

        if (inputs.length < 3) {
            // Delegate to padded path for small windows
            return filterPadded(inputs);
        }

        double fcDynamic = baseFc;
        if (autoEnabled) {
            double variance = calcVariance(inputs);
            double delta = adaptGain * Math.sqrt(variance);
            fcDynamic = baseFc + delta;
        }

        // Stability clamps
        if (fs <= 0) fs = 1.0;
        double nyquist = fs / 2.0;
        if (fcDynamic < 1e-6) fcDynamic = 1e-6;
        if (fcDynamic >= nyquist) fcDynamic = nyquist * 0.99;

        // Update coefficients once for the batch
        calculateCoefficients(fs, fcDynamic);

        double out = 0.0;
        for (double v : inputs) {
            double y = super.applyFilter(FilterUtils.sanitize(v, lastOutput));
            lastOutput = y;
            out = y;
        }
        return out;
    }

    /**
     * Short-window padded buffer filtering for len < 3:
     *  - len==1 → [xN, xN, xN]
     *  - len==2 → [xN-1, xN-1, xN]
     *
     * Uses per-sample adaptive path semantics, i.e. each padded sample
     * will still be processed through applyFilter→calculateCoefficients
     * (if autoEnabled), so adaptation remains consistent.
     */
    public double filterPadded(double[] inputs) {
        if (inputs == null || inputs.length == 0)
            return (lastOutput != null) ? lastOutput : 0.0;

        if (inputs.length >= 3) {
            // Fallback to normal buffer path
            return filter(inputs);
        }

        double xN   = FilterUtils.sanitize(inputs[inputs.length - 1], lastOutput);
        double xNm1 = (inputs.length >= 2) ? FilterUtils.sanitize(inputs[inputs.length - 2], lastOutput) : xN;

        double[] tri = (inputs.length == 1)
                ? new double[]{xN, xN, xN}
                : new double[]{xNm1, xNm1, xN};

        double out = 0.0;
        for (double v : tri) {
            double y = super.applyFilter(FilterUtils.sanitize(v, lastOutput)); // direct parent math
            lastOutput = y;
            out = y;
        }
        return out;
    }

    /**
     * Update parameters in place.
     * Accepts (fs, fc) or (fs, fc, gain). Resets histories to avoid transients.
     */
    @Override
    public void updateParameters(double[] args) {
        if (args == null) return;
        if (!(args.length == 2 || args.length == 3)) return;

        double newFs = (args[0] > 0.0) ? args[0] : this.fs;
        double newFc = (args[1] > 0.0) ? args[1] : this.baseFc;

        double ny = newFs / 2.0;
        if (newFc >= ny) newFc = ny * 0.99;

        this.fs = newFs;
        this.baseFc = newFc;

        if (args.length == 3) {
            double g = args[2];
            if (g <= 0.0) {
                this.adaptGain = 0.0;
                this.autoEnabled = false;
            } else {
                this.adaptGain = Math.min(g, 1.0);
                this.autoEnabled = true;
            }
        }

        calculateCoefficients(this.fs, this.baseFc);
        reset();
    }

    /**
     * Compute sample variance with NaN/Inf safety.
     */
    private double calcVariance(double[] data) {
        if (data.length < 2) return 0.0;

        double mean = 0.0;
        int valid = 0;
        for (double v : data) {
            double x = FilterUtils.sanitize(v, lastOutput);
            mean += x;
            valid++;
        }
        if (valid == 0) return 0.0;
        mean /= valid;

        double var = 0.0;
        for (double v : data) {
            double x = FilterUtils.sanitize(v, lastOutput);
            double d = x - mean;
            var += d * d;
        }
        return var / valid; // unbiased correction is not critical for adaptation
    }

}
