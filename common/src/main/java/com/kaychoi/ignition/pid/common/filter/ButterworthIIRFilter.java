package com.kaychoi.ignition.pid.common.filter;

import com.inductiveautomation.ignition.common.expressions.ExpressionException;

/**
 * Second-order Butterworth IIR low-pass filter.
 * - Uses bilinear transform-style coefficient generation
 * - Keeps 3-sample input and output history
 * - Supports parameter update and reset
 * - Short-input padding helper for len=1 or len=2 windows.
 *
 * Args (mode=3):
 *   args[0] = sampling frequency (fs, Hz)      > 0
 *   args[1] = cutoff frequency   (fc, Hz)      0 < fc < fs/2
 *
 * Behavior:
 *   - If inputs length == 1 → [xN, xN, xN] padded
 *   - If inputs length == 2 → [xN-1, xN-1, xN] padded
 *   - Normal filtering otherwise
 */
public class ButterworthIIRFilter extends AbstractFilter {

    /** Small epsilon to prevent edge cases near Nyquist */
    private static final double EPS = 1e-12;

    // Denominator (a) and numerator (b) coefficients
    private final double[] a = new double[3];
    private final double[] b = new double[3];

    // Input/output history buffers
    private final double[] x = new double[3];
    private final double[] y = new double[3];

    // Keep last valid fs/fc to avoid needless resets when parameters don't change meaningfully
    private double fsStored;
    private double fcStored;

    public ButterworthIIRFilter(double fs, double fc) throws ExpressionException {
        double[] v = validateAndClampFsFc(fs, fc);
        this.fsStored = v[0];
        this.fcStored = v[1];
        calculateCoefficients(this.fsStored, this.fcStored);
    }

    /**
     * Validate and clamp fs/fc for numerical safety.
     * The goal is not to silently fix obviously broken inputs, but to:
     *  - reject non-finite/negative values,
     *  - prevent fc from hitting Nyquist exactly (where tan(pi*fc/fs) explodes),
     *  - ensure fc is strictly positive.
     *
     * @return a 2-element array {fsSafe, fcSafe}
     */
    private static double[] validateAndClampFsFc(double fs, double fc) throws ExpressionException {
        if (!Double.isFinite(fs) || !Double.isFinite(fc)) {
            throw new ExpressionException("fs/fc must be finite real numbers");
        }
        if (fs <= 0.0) {
            throw new ExpressionException("Sampling frequency fs must be > 0");
        }
        // Keep fc within (0, fs/2 - EPS). We use a tiny guard against Nyquist.
        double nyquist = fs * 0.5;
        if (fc <= 0.0) {
            throw new ExpressionException("Cutoff frequency fc must be > 0");
        }
        if (fc >= nyquist) {
            // Provide a clear error rather than silently clamping across a large gap
            // (helps a user fix their configuration).
            throw new ExpressionException("Cutoff frequency fc must be < fs/2 (Nyquist)");
        }
        // Gentle protection against extreme closeness to Nyquist for numerical stability in tan()
        double fcSafe = Math.min(fc, nyquist - Math.max(EPS, nyquist * 1e-12));
        return new double[]{fs, fcSafe};
    }

    /**
     * Compute coefficients for a 2nd-order Butterworth LPF.
     * This uses a classic analog-prototype → bilinear transform approach.
     */
    protected void calculateCoefficients(double fs, double fc) {
        double ita = 1.0 / Math.tan(Math.PI * fc / fs);
        double q = Math.sqrt(2.0);

        // Normalize so that a0 == 1 in standard form
        double b0 = 1.0 / (1.0 + q * ita + ita * ita);
        double b1 = 2.0 * b0;
        double b2 = b0;
        double a1 = 2.0 * b0 * (1.0 - ita * ita);
        double a2 = b0 * (1.0 - q * ita + ita * ita);

        // Feedforward
        b[0] = b0;
        b[1] = b1;
        b[2] = b2;
        a[0] = 1.0;

        // Feedback stored as NEGATED to simplify difference equation sign
        a[1] = -a1;
        a[2] = -a2;
    }

    /**
     * Apply Butterworth filtering for a single input value.
     * Keeps track of past 3 samples internally.
     */
    @Override
    protected double applyFilter(double input) {
        double xVal = FilterUtils.sanitize(input, lastOutput);

        // Shift input history: x2 <- x1, x1 <- x0, x0 <- xVal
        x[2] = x[1];
        x[1] = x[0];
        x[0] = xVal;

        // Shift output history: y2 <- y1, y1 <- y0
        y[2] = y[1];
        y[1] = y[0];

        // Difference equation:
        // y[n] = b0*x[n] + b1*x[n-1] + b2*x[n-2] + a1*y[n-1] + a2*y[n-2]
        y[0] = b[0] * x[0] + b[1] * x[1] + b[2] * x[2]
                + a[1] * y[1] + a[2] * y[2];

        return y[0];
    }

    /**
     * Filter a short input buffer with padding.
     * len==1 → [xN, xN, xN]
     * len==2 → [xN-1, xN-1, xN]
     * len>=3 → normal sequential filtering
     */
    public double filterPadded(double[] inputs) {
        if (inputs == null || inputs.length == 0) {
            return (lastOutput != null) ? lastOutput : 0.0;
        }

        // len >= 3 → process normally
        if (inputs.length >= 3) {
            double out = 0.0;
            for (double v : inputs) {
                out = super.filter(FilterUtils.sanitize(v, lastOutput));
            }
            return out;
        }

        // len == 1 or len == 2 → build padded window
        double xN = FilterUtils.sanitize(inputs[inputs.length - 1], lastOutput);
        double xNm1 = (inputs.length >= 2) ? FilterUtils.sanitize(inputs[inputs.length - 2], lastOutput) : xN;

        double[] tri = (inputs.length == 1)
                ? new double[]{xN, xN, xN}
                : new double[]{xNm1, xNm1, xN};

        double out = 0.0;
        for (double v : tri) {
            out = super.filter(v);
        }
        return out;
    }

    /**
     * Update filter coefficients dynamically (fs, fc)
     * - Validates inputs.
     * - Only recalculates coefficients and resets history if values actually changed.
     */
    @Override
    public void updateParameters(double[] args) throws ExpressionException {
        if (args == null || args.length < 2) return;

        // We do not call FilterUtils.sanitize on parameters; arguments must be valid on their own.
        double fsNew = args[0];
        double fcNew = args[1];

        double[] v = validateAndClampFsFc(fsNew, fcNew);
        fsNew = v[0];
        fcNew = v[1];

        // Avoid needless resets if values haven't meaningfully changed
        final double tolRel = 1e-9;
        boolean sameFs = Math.abs(fsNew - fsStored) <= tolRel * Math.max(1.0, Math.abs(fsStored));
        boolean sameFc = Math.abs(fcNew - fcStored) <= tolRel * Math.max(1.0, Math.abs(fcStored));

        // No-op: keep current coefficients and history to minimize transients
        if (sameFs && sameFc) return;

        // Recompute coefficients with the new parameters
        calculateCoefficients(fsNew, fcNew);

        // Update stored params
        fsStored = fsNew;
        fcStored = fcNew;

        // Clear histories to avoid mismatch transients due to new coeffs
        reset();
    }

    /**
     * Reset internal state (history and lastOutput).
     * Keeps current coefficients.
     */
    @Override
    public void reset() {
        super.reset();
        for (int i = 0; i < 3; i++) {
            x[i] = 0.0;
            y[i] = 0.0;
        }
    }

    /**
     * Convenience method: reset and prefill internal history.
     * Useful to avoid initial dips when filter starts from zero.
     */
    public void reset(double initialValue) {
        super.reset();
        for (int i = 0; i < 3; i++) {
            x[i] = initialValue;
            y[i] = initialValue;
        }
    }

}
