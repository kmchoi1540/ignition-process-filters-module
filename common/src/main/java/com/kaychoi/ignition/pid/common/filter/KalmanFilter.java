package com.kaychoi.ignition.pid.common.filter;

import com.inductiveautomation.ignition.common.expressions.ExpressionException;

/**
 * KalmanFilter (2D Position–Velocity Model)
 * ------------------------------------------------------------
 * A 2D discrete-time Kalman filter implementation designed for
 * smoothing and predicting process signals (e.g., temperature, flow, level)
 * that exhibit first-order or second-order dynamics.
 *
 * ▣ System Model
 *   - State vector: x = [ position, velocity ]^T
 *   - Measurement:  z = position + noise
 *   - State evolution (discrete-time):
 *
 *       x_k = F * x_{k-1} + w_k
 *       z_k = H * x_k + v_k
 *
 *     where:
 *       F = [[1, dt],
 *            [0,  1]]
 *       H = [1, 0]
 *
 *     Process noise:      w_k ~ N(0, Q)
 *     Measurement noise:  v_k ~ N(0, R)
 *
 * ▣ Arguments (mode=4)
 *   [ dt(ms), Q, R ]
 *     - dt: Sampling interval between two measurements, in milliseconds
 *     - Q : Process noise variance (represents model uncertainty)
 *     - R : Measurement noise variance (represents sensor uncertainty)
 *
 * ▣ Behavior
 *   - Q controls how aggressively the filter reacts to changes.
 *     Larger Q → faster tracking, but more noise in output.
 *   - R controls how smooth the output is.
 *     Larger R → smoother, slower response.
 *
 * ▣ Implementation Notes
 *   - Supports Q, R ≥ 0 with internal clamping for stability.
 *   - Negative values are clamped to 0.
 *   - Small epsilon (EPS) prevents division by zero and ensures
 *     numerical robustness during covariance updates.
 */
public class KalmanFilter extends AbstractFilter {

    /** Small epsilon to prevent division-by-zero and matrix singularity */
    private static final double EPS = 1e-12;

    /** Sampling interval (seconds) = dt(ms) / 1000 */
    private double dt;

    /** Process noise variance (≥ 0). Represents uncertainty in system model. */
    private double Q;

    /** Measurement noise variance (≥ 0). Represents uncertainty in sensor readings. */
    private double R;

    /** State vector x = [ position, velocity ]^T */
    private final double[] x = new double[2];

    /** Covariance matrix P (2×2), represents state estimation uncertainty */
    private final double[][] P = new double[2][2];

    /** State transition matrix F = [[1, dt], [0, 1]] */
    private final double[][] F = new double[2][2];

    /** Observation matrix H = [1, 0] */
    private final double[] H = new double[]{1.0, 0.0};

    /**
     * Constructor — initializes Kalman filter parameters and state.
     *
     * @param dtMs Sampling interval in milliseconds. Must be > 0.
     * @param Q Process noise variance (≥ 0). Larger Q = faster but noisier.
     * @param R Measurement noise variance (≥ 0). Larger R = smoother but slower.
     *
     * If Q or R are negative, they will be clamped to 0.
     * EPS (1e-12) is used internally to ensure numerical stability.
     *
     * @throws ExpressionException if dt ≤ 0.
     */
    public KalmanFilter(int dtMs, double Q, double R) throws ExpressionException {
        if (dtMs <= 0)
            throw new ExpressionException("dt must be positive (ms)");
        this.dt = dtMs / 1000.0;
        this.Q = Math.max(Q, 0.0);
        this.R = Math.max(R, 0.0);
        reset();
    }

    /**
     * ▣ Prediction step
     * Propagates the state and covariance matrix forward in time.
     *
     *  x_pred = F * x_prev
     *  P_pred = F * P_prev * F^T + Q
     *
     * Since Q is treated as a scalar simplification, it is directly added to the diagonal.
     */
    protected void predict() {
        // Update transition matrix (depends on current dt)
        F[0][0] = 1.0;
        F[0][1] = dt;
        F[1][0] = 0.0;
        F[1][1] = 1.0;

        // Predict state
        double x0_pred = x[0] + dt * x[1];
        double x1_pred = x[1];

        // Predict covariance (simplified form with scalar Q)
        double P00p = P[0][0] + dt * (P[1][0] + P[0][1]) + dt * dt * P[1][1] + Q;
        double P01p = P[0][1] + dt * P[1][1];
        double P10p = P[1][0] + dt * P[1][1];
        double P11p = P[1][1] + Q;

        // Commit predicted state and covariance
        x[0] = x0_pred;
        x[1] = x1_pred;
        P[0][0] = P00p;
        P[0][1] = P01p;
        P[1][0] = P10p;
        P[1][1] = P11p;
    }

    /**
     * ▣ Update (correction) step
     * Incorporates the new measurement z into the predicted state.
     *
     *  y = z - H * x_pred          (innovation)
     *  S = H * P_pred * H^T + R    (innovation covariance)
     *  K = P_pred * H^T / S        (Kalman gain)
     *  x_new = x_pred + K * y
     *  P_new = (I - K * H) * P_pred
     *
     * EPS ensures S does not approach zero (numerical protection).
     *
     * @param z Current measurement (process variable, e.g., temperature)
     */
    public void update(double z) {
        double y = z - (H[0] * x[0] + H[1] * x[1]);
        double S = Math.max(P[0][0] * H[0] * H[0] + R, EPS);  // Prevent divide-by-zero
        double K0 = (P[0][0] * H[0]) / S;
        double K1 = (P[1][0] * H[0]) / S;

        // State update
        x[0] += K0 * y;
        x[1] += K1 * y;

        // Covariance update
        double P00p = P[0][0];
        double P01p = P[0][1];
        double P10p = P[1][0];
        double P11p = P[1][1];

        P[0][0] = (1.0 - K0 * H[0]) * P00p;
        P[0][1] = (1.0 - K0 * H[0]) * P01p;
        P[1][0] = P10p - K1 * H[0] * P00p;
        P[1][1] = P11p - K1 * H[0] * P01p;
    }

    /**
     * Applies the Kalman filter to one input sample.
     * Handles initialization, prediction, and correction automatically.
     *
     * @param input Current process variable (PV)
     * @return Filtered (smoothed) estimate of position
     */
    @Override
    protected double applyFilter(double input) {
        // Sanitize input: use lastOutput if input is invalid (NaN, etc.)
        double prev = (lastOutput == null) ? input : lastOutput;
        double z = FilterUtils.sanitize(input, prev);

        // Initialize on first sample
        if (lastOutput == null) {
            x[0] = z;
            x[1] = 0.0; // assume stationary start
            P[0][0] = 1.0;  P[0][1] = 0.0;
            P[1][0] = 0.0;  P[1][1] = 1.0;
            lastOutput = z;
            return z;
        }

        // Perform standard Kalman predict-update cycle
        predict();
        update(z);

        // Save latest estimate
        lastOutput = x[0];
        return x[0];
    }

    /**
     * Dynamically updates parameters (dt, Q, R) during runtime.
     * Used when arguments change in Ignition expression.
     * Clamps negative values to 0 to maintain stability.
     *
     * @param args [dt(ms), Q, R]
     */
    @Override
    public void updateParameters(double[] args) {
        if (args != null && args.length >= 3) {
            double newDtSec = Math.max(1e-6, args[0] / 1000.0);
            double newQ = Math.max(args[1], 0.0);
            double newR = Math.max(args[2], 0.0);

            this.dt = newDtSec;
            this.Q = newQ;
            this.R = newR;
        }
    }

    /**
     * Resets the filter to its initial (uninitialized) state.
     * Typically called when 'enable' becomes false or UDT reinitializes.
     * Does not throw; always safe.
     */
    @Override
    public void reset() {
        super.reset();  // sets lastOutput = null
        x[0] = 0.0;
        x[1] = 0.0;
        P[0][0] = 1.0;  P[0][1] = 0.0;
        P[1][0] = 0.0;  P[1][1] = 1.0;
    }

    // --------------------------------------------------------------------
    // Accessors
    // --------------------------------------------------------------------

    /** @return Current estimated position (state x₀). */
    public double getPosition() { return x[0]; }

    /** @return Current estimated velocity (state x₁). */
    public double getVelocity() { return x[1]; }

    /** @return Current state covariance matrix (2×2). */
    public double[][] getCovariance() { return P; }
}
