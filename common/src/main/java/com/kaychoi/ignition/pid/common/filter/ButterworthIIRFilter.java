package com.kaychoi.ignition.pid.common.filter;

/**
 * Second-order Butterworth IIR low-pass filter.
 * - Uses bilinear transform-style coefficient generation
 * - Keeps 3-sample input and output history
 * - Supports parameter update and reset
 * - Supports short input padding for len=1 or len=2
 *
 * Args (mode=3):
 *   args[0] = sampling frequency (fs, Hz)
 *   args[1] = cutoff frequency   (fc, Hz)
 *
 * Behavior:
 *   - If inputs length == 1 → [xN, xN, xN] padded
 *   - If inputs length == 2 → [xN-1, xN-1, xN] padded
 *   - Normal filtering otherwise
 */
public class ButterworthIIRFilter extends AbstractFilter {

    // Denominator (a) and numerator (b) coefficients
    private final double[] a = new double[3];
    private final double[] b = new double[3];

    // Input/output history buffers
    private final double[] x = new double[3];
    private final double[] y = new double[3];

    public ButterworthIIRFilter(double fs, double fc) {
        if (fs <= 0 || fc <= 0 || fc >= fs / 2) {
            throw new IllegalArgumentException("Invalid sampling frequency or cutoff frequency");
        }
        calculateCoefficients(fs, fc);
    }

    /**
     * Compute coefficients for a 2nd-order Butterworth LPF.
     * This uses a classic analog-prototype → bilinear transform approach.
     */
    protected void calculateCoefficients(double fs, double fc) {
        double ita = 1.0 / Math.tan(Math.PI * fc / fs);
        double q = Math.sqrt(2.0);

        double b0 = 1.0 / (1.0 + q * ita + ita * ita);
        double b1 = 2.0 * b0;
        double b2 = b0;
        double a1 = 2.0 * b0 * (1.0 - ita * ita);
        double a2 = b0 * (1.0 - q * ita + ita * ita);

        b[0] = b0;
        b[1] = b1;
        b[2] = b2;
        a[0] = 1.0;
        // Store negated for simpler difference-equation
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

        // Shift input history
        x[2] = x[1];
        x[1] = x[0];
        x[0] = xVal;

        // Shift output history
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
            for (double v : inputs)
                out = super.filter(FilterUtils.sanitize(v, lastOutput));
            return out;
        }

        // len == 1 or len == 2 → build padded window
        double xN = FilterUtils.sanitize(inputs[inputs.length - 1], lastOutput);
        double xNm1 = (inputs.length >= 2) ? FilterUtils.sanitize(inputs[inputs.length - 2], lastOutput) : xN;

        double[] tri;
        if (inputs.length == 1) {
            tri = new double[]{xN, xN, xN};
        } else { // len == 2
            tri = new double[]{xNm1, xNm1, xN};
        }

        double out = 0.0;
        for (double v : tri)
            out = super.filter(v);
        return out;
    }

    /**
     * Update filter coefficients dynamically (fs, fc)
     */
    @Override
    public void updateParameters(double[] args) {
        if (args != null && args.length == 2) {
            calculateCoefficients(args[0], args[1]);
            reset();  // clear histories to avoid transients with mismatched coeffs
        }
    }

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
