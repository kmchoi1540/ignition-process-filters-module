package com.kaychoi.ignition.pid.common.filter;

/**
 * Adaptive Butterworth IIR Filter
 *
 * Extends ButterworthIIRFilter and adds optional auto-tuning
 * of the cutoff frequency (fc) based on input signal variance.
 *
 * Modes:
 *  - Manual:   new AdaptiveButterworthFilter(fs, fc, null)
 *  - Adaptive: new AdaptiveButterworthFilter(fs, baseFc, adaptGain)
 *
 * Args:
 *  - fs : Sampling rate (Hz) > 0
 *  - fc : Base cutoff frequency (Hz) > 0 and < fs/2
 *  - adaptGain : Optional gain factor for fc tuning (null or <=0 → manual mode)
 *
 * Notes:
 *  - If adaptGain ≤ 0 or null → fixed manual filtering (no adaptation)
 *  - If adaptGain ≥ 1 → clamped to 1.0 for stability
 */
public class AdaptiveButterworthFilter extends ButterworthIIRFilter {

    private double fs;
    private double baseFc;
    private Double adaptGain;     // null means manual mode
    private boolean autoEnabled;

    public AdaptiveButterworthFilter(double fs, double baseFc, Double adaptGain) {
        super(fs, baseFc); // Fixed Butterworth initialization
        this.fs = fs;
        this.baseFc = baseFc;

        // Null or non-positive → manual mode
        if (adaptGain == null || adaptGain <= 0.0) {
            this.adaptGain = 0.0;
            this.autoEnabled = false;
        } else {
            // Clamp upper bound (avoid excessive reactivity)
            this.adaptGain = Math.min(adaptGain, 1.0);
            this.autoEnabled = true;
        }
    }

    /**
     * Apply filtering over the entire buffer.
     * If autoEnabled, adjusts cutoff frequency based on variance.
     */
    public double filter(double[] inputs) {
        if (inputs == null || inputs.length == 0)
            return (lastOutput != null) ? lastOutput : 0.0;

        double fcDynamic = baseFc;

        // Auto mode: variance-based cutoff adjustment
        if (autoEnabled && adaptGain > 0.0) {
            double variance = calcVariance(inputs);
            double delta = adaptGain * Math.sqrt(variance);

            // Smooth scaling of fc based on variance
            fcDynamic = baseFc + delta;
        }

        // Enforce Butterworth stability (0 < fc < fs/2)
        if (fs <= 0) fs = 1.0;
        double nyquist = fs / 2.0;
        if (fcDynamic < 1e-6) fcDynamic = 1e-6;
        if (fcDynamic >= nyquist) fcDynamic = nyquist * 0.99;

        // Recalculate coefficients safely
        calculateCoefficients(fs, fcDynamic);

        double out = 0.0;
        for (double v : inputs)
            out = super.filter(v);

        return out;
    }

    /**
     * Compute variance of the input buffer.
     */
    private double calcVariance(double[] data) {
        if (data.length < 2) return 0.0;
        double mean = 0.0;
        for (double v : data) mean += v;
        mean /= data.length;

        double var = 0.0;
        for (double v : data) var += (v - mean) * (v - mean);
        return var / (data.length - 1);
    }
}
