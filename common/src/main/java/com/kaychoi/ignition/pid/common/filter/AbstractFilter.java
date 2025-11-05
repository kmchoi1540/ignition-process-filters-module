package com.kaychoi.ignition.pid.common.filter;

import javax.lang.model.type.NullType;
import java.util.logging.Logger;
import com.inductiveautomation.ignition.common.expressions.ExpressionException;

/**
 * Base class for all custom filters.
 * - Provides common logging
 * - Stores last output (useful for single-pole filters)
 * - Enforces updateable parameters
 *
 * Concrete filters must implement:
 *  - applyFilter(double input)
 *  - updateParameters(double[] args)
 */
public abstract class AbstractFilter {

    protected final Logger logger = Logger.getLogger(getClass().getName());

    /** Last output value produced by this filter (can be null at startup). */
    protected Double lastOutput = null;

    /**
     * Public entry point for applying the filter.
     * This calls the subclass' applyFilter(...) and caches the result.
     *
     * @param input current input sample
     * @return filtered output
     */
    public double filter(double input) {
        double filteredValue = applyFilter(input);
        lastOutput = filteredValue;
        return filteredValue;
    }

    /**
     * Resets filter internal state to "uninitialized".
     * Subclasses can override to clear their own buffers.
     */
    public void reset() {
        lastOutput = null;
    }

    /**
     * Actual filter math goes here.
     */
    protected abstract double applyFilter(double input);

    /**
     * Updates filter parameters in-place if possible.
     * Filters that cannot update in-place may choose to ignore the input
     * or reinitialize internal coefficients.
     *
     * @param args array of new arguments
     */
    public abstract void updateParameters(double[] args) throws ExpressionException;

    /**
     * Helper to safely extract a Double from various dynamic runtime types.
     * We sometimes get null, "null", "none", "NaN", or Jython Null.
     */
    protected Double extractDouble(Object input) {
        if (input == null) return null;
        if (input instanceof NullType) return null;

        if (input instanceof Number) {
            double val = ((Number) input).doubleValue();
            return Double.isNaN(val) ? null : val;
        }

        if (input instanceof String) {
            String str = ((String) input).trim();
            if (str.isEmpty()) return null;

            char firstChar = str.charAt(0);
            // Handle "nan", "null", "none"
            if ((firstChar == 'n') || (firstChar == 'N')) {
                if (str.equalsIgnoreCase("nan")
                        || str.equalsIgnoreCase("null")
                        || str.equalsIgnoreCase("none")) {
                    return null;
                }
            }
            try {
                double val = Double.parseDouble(str);
                return Double.isNaN(val) ? null : val;
            } catch (NumberFormatException e) {
                logger.warning("Invalid input '" + input + "'");
                return null;
            }
        }
        return null;
    }
}
