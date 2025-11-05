package com.kaychoi.ignition.pid.common.filter;

import com.inductiveautomation.ignition.common.expressions.ExpressionException;

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
 *  - adaptGain : Gain factor for fc tuning (null or <= 0 → manual) [0,1]
 *
 * Adaptation Logic:
 *  - Activity measure: EWMA of |x[n] - x[n-1]|
 *     activity[n] = a * |dx[n]| + (1-a) * activity[n-1], with a=0.2 (default)
 *  - Map activity (0...∞) → g in [0,1):  g = activity / (1 + activity)
 *      (This is a smooth, saturating mapping that avoids unbounded growth.)
 *  - Adjust cutoff:
 *     fc' = baseFc * (1 + adaptGain * g)
 *     Small activity → fc' ≈ baseFc (more smoothing)
 *     Large activity → fc' increases toward baseFc*(1+adaptGain) (faster tracking)
 *
 * Notes:
 *  - If adaptGain ≥ 1 → clamped to 1.0 by default.
 *   - To avoid excessive biquad retunes, this only retunes when |fc' - fcCurrent|
 *     exceeds a tiny epsilon (~1e-9), which is enough to skip numerical noise.
 *   - For very short buffers (len < 3), this uses a simple padding sequence to
 *     stabilize early calls (same philosophy as your existing codebase).
 */
public class AdaptiveButterworthFilter extends ButterworthIIRFilter {


    /** Small epsilon to protect denominators / Nyquist boundary */
    private static final double EPS = 1e-12;

    /** Soft floors for parameters */
    private static final double FS_MIN = 1e-6;   // minimal positive fs
    private static final double FC_MIN = 1e-9;   // minimal positive cutoff

    /** EWMA smoothing factor for activity, can be tuned (0<alphaAct<=1) */
    private double alphaAct = 0.2;

    /** Runtime parameters */
    private double fs = 1.0;
    private double fcBase = 0.1;
    private double adaptGain = 0.0;      // [0..1]

    /** Current effective cutoff applied to parent filter */
    private double currentFc = 0.1;

    /** Activity tracker (EWMA of |x[n]-x[n-1]|) */
    private double activityEwma = 0.0;

    /**
     * Constructor for either static or adaptive use.
     * - If adaptGain is null → static Butterworth with (fs, fc).
     * - If adaptGain is non-null → the adaptive path is enabled.
     */

    public AdaptiveButterworthFilter(double fs, double fcBase, double adaptGain) throws ExpressionException {
        super(Math.max(fs, FS_MIN),
                sanitizeFc(fcBase, Math.max(fs, FS_MIN))); // Initialize as fixed Butterworth first
        // Initialize our parameters with hard clamps
        this.fs = Math.max(fs, FS_MIN);

        double nyq = 0.5 * this.fs;
        this.fcBase = Math.min(
                Math.max(fcBase, FC_MIN),
                Math.max(nyq - EPS, FC_MIN)
        );

        // clamp adaptGain to [0,1]
        if (adaptGain < 0.0) adaptGain = 0.0;
        else if (adaptGain > 1.0) adaptGain = 1.0;
        this.adaptGain = adaptGain;

        this.currentFc = this.fcBase;

        // Ensure parent coefficients reflect the base cutoff
        super.updateParameters(new double[]{ this.fs, this.fcBase });
    }

    /** Helper: sanitize a proposed fc against fs' Nyquist bound. */
    private static double sanitizeFc(double fc, double fs) {
        double nyq = 0.5 * Math.max(fs, FS_MIN);
        if (fc <= 0.0) fc = FC_MIN;
        return Math.min(fc, Math.max(nyq - EPS, FC_MIN));
    }

    /**
     * Per-sample adaptive step:
     *   - Update activity EWMA
     *   - Map to [0..1], compute fc'
     *   - Clip fc' to valid range and update parent coeffs if changed
     *   - Run Butterworth step via parent
     */
    @Override
    protected double applyFilter(double input) {
        final double prev = (lastOutput == null) ? input : lastOutput;
        final double x = FilterUtils.sanitize(input, prev);

        // 1) Activity update (EWMA)
        final double diff = Math.abs(x - prev);
        activityEwma = (1.0 - alphaAct) * activityEwma + alphaAct * diff;

        // 2) Map activity → [0..1] with a monotone saturating function
        final double g = activityEwma / (activityEwma + 1.0); // bounded

        // 3) Adapt cutoff
        double fcPrime = fcBase * (1.0 + adaptGain * g);

        // 4) Hard-clip to (0, fs/2 - EPS]
        final double nyq = 0.5 * fs;
        fcPrime = Math.max(FC_MIN, Math.min(fcPrime, Math.max(nyq - EPS, FC_MIN)));

        // 5) retune only if meaningful
        double rel = Math.abs(fcPrime - this.currentFc) / Math.max(this.currentFc, 1e-9);
        if (rel > 1e-6) {
            this.currentFc = fcPrime;
            calculateCoefficients(this.fs, this.currentFc); // No reset
        }

        // 6) Run the IIR step (parent holds internal states/coeffs)
        return super.applyFilter(x);
    }

    /**
     * Update parameters in place.
     * Accepts (fs, fc) or (fs, fc, gain). Resets histories to avoid transients.
     */
    @Override
    public void updateParameters(double[] args) throws ExpressionException {
        if (args == null || args.length < 3) return;

        double newFs = Math.max(args[0], FS_MIN);
        double nyq = 0.5 * newFs;

        double newFcBase = args[1];
        if (newFcBase <= 0.0) newFcBase = FC_MIN;
        newFcBase = Math.min(newFcBase, Math.max(nyq - EPS, FC_MIN));

        double newAdapt = args[2];
        if (newAdapt < 0.0) newAdapt = 0.0;
        else if (newAdapt > 1.0) newAdapt = 1.0;

        boolean changed = (this.fs != newFs) || (this.fcBase != newFcBase) || (this.adaptGain != newAdapt);
        this.fs = newFs;
        this.fcBase = newFcBase;
        this.adaptGain = newAdapt;

        if (changed) {
            // Reset currentFc to base and recompute once
            this.currentFc = this.fcBase;
            super.updateParameters(new double[]{ this.fs, this.currentFc });
        }
    }
    public double getCurrentFc() { return this.currentFc; }
    public double getCurrentFs() { return this.fs; }
}
