package com.kaychoi.ignition.pid.common.filter;

import com.inductiveautomation.ignition.common.TypeUtilities;
import com.inductiveautomation.ignition.common.expressions.Expression;
import com.inductiveautomation.ignition.common.expressions.ExpressionException;
import com.inductiveautomation.ignition.common.expressions.functions.AbstractFunction;
import com.inductiveautomation.ignition.common.model.values.BasicQualifiedValue;
import com.inductiveautomation.ignition.common.model.values.QualifiedValue;
import com.inductiveautomation.ignition.common.model.values.QualityCode;
import com.kaychoi.ignition.pid.common.TimedObjectManager;
import com.kaychoi.ignition.pid.common.UUIDKeyManager;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Expression Function:
 * customFilter(enable:boolean, mode:int, size:int, args:list/document, inputs:list, [uniqueId:string])
 *
 * Modes:
 *  1 - Current Weighted Moving Average
 *  2 - Weighted Moving Average
 *  3 - Butterworth IIR (static/adaptive)
 *  4 - Kalman (2D position-velocity)
 */

public class CustomFilterFunction extends AbstractFunction {

    private static final Logger LOGGER = Logger.getLogger(CustomFilterFunction.class.getName());
    private static final TimedObjectManager<FilterState> FILTER_MANAGER =
            new TimedObjectManager<>(60 * 60 * 1000L, 10 * 60 * 1000L, "CustomFilter-Cleanup");

    private static class FilterState {
        AbstractFilter filter;
        int mode;
        double[] args;
        int inputLen;
    }

    @Override public Class<?> getType() { return Double.class; }
    @Override protected String getFunctionDisplayName() { return "customFilter"; }
    @Override public String getArgDocString() { return "enable:boolean, mode:int, size:int, args:document/list, inputs:list, [uniqueId:string]"; }
    @Override protected boolean validateNumArgs(int n) { return n == 5 || n == 6; }

    @Override
    public QualifiedValue execute(Expression[] exprs) {
        try {
            return doExecute(exprs);
        } catch (ExpressionException e1) {
            LOGGER.log(Level.WARNING, "customFilter: Expression error - " + e1.getMessage(), e1);
            return new BasicQualifiedValue(0.0, QualityCode.Bad.derive("customFilter: " + e1.getMessage()));
        } catch (RuntimeException e2) {
            Throwable root = (e2.getCause() != null) ? e2.getCause() : e2;
            String msg = (root.getMessage() != null) ? root.getMessage() : root.getClass().getName();
            LOGGER.log(Level.WARNING, "customFilter: Runtime error - " + msg, root);
            return new BasicQualifiedValue(0.0, QualityCode.Bad.derive("customFilter: " + msg));
        }
    }

    private QualifiedValue doExecute(Expression[] e) throws ExpressionException {
        boolean enable = TypeUtilities.toBool(e[0].execute().getValue());
        int mode = TypeUtilities.toInteger(e[1].execute().getValue());
        int size = TypeUtilities.toInteger(e[2].execute().getValue());
        Object rawArgs = e[3].execute().getValue();
        Object rawInputs = e[4].execute().getValue();
        String uniqueId = (e.length == 6)
                ? String.valueOf(e[5].execute().getValue())
                : UUID.randomUUID().toString();

        UUID uuid = UUIDKeyManager.getOrCreateUUID("customFilter", uniqueId);

        List<?> argsList = FilterUtils.normalizeToList(rawArgs);
        double[] inputs = FilterUtils.toDoubleArray(rawInputs);
        //No sotred value on initial execution
        if (inputs.length == 0) {
            return new BasicQualifiedValue(0.0, QualityCode.Good);
        }

        int effectiveLen = Math.min(size, inputs.length);
        double[] limited;
        if (size > 0 && inputs.length > size) {
            // copy tail only when stored data are not filled completely.
            limited = new double[effectiveLen];
            System.arraycopy(inputs, inputs.length - effectiveLen, limited, 0, effectiveLen);
        } else {
            limited = inputs;
        }

        double[] argsArray = parseArgsToDoubleArray(argsList);

        // When disabled, delete occupying UUID & information.
        if (!enable) {
            FILTER_MANAGER.remove(uuid, null);
            UUIDKeyManager.remove("customFilter", uniqueId);
            double last = limited.length > 0 ? limited[limited.length - 1] : 0.0;
            return new BasicQualifiedValue(last, QualityCode.Good);
        }

        int argsLength = argsArray.length;

        // Argument validation
        if ((mode == 1 || mode == 2) && argsLength != 1)
            throw new ExpressionException("Mode " + mode + " requires 1 argument.");
        if (mode == 3 && !(argsLength == 2 || argsLength == 3))
            throw new ExpressionException("Mode 3 requires 2 or 3 arguments: [fs, fc, (optional adaptGain)].");
        if (mode == 4 && !(argsLength == 3 || argsLength == 4 || argsLength == 6))
            throw new ExpressionException("Mode 4 requires 3 or 4 or 6 arguments: [dt, Q, R, (optional adaptGain or alpha, beta, kappa)].");

        // Create or retrieve filter state
        FilterState state = FILTER_MANAGER.getOrCreate(uuid, () -> {
            FilterState fs = new FilterState();
            try {
                fs.filter = createFilter(mode, argsArray, limited.length);
            } catch (ExpressionException ex) {
                throw new RuntimeException(ex);
            }
            fs.mode = mode;
            fs.args = argsArray;
            fs.inputLen = limited.length;
            return fs;
        });

        // Detect configuration(mode, inputLength, args) changes
        boolean rebuild = (state.mode != mode) || (state.inputLen != effectiveLen)
                || (state.args == null) || (state.args.length != argsLength)
                || !Arrays.equals(state.args, argsArray);


        if (rebuild) {
            state.filter = createFilter(mode, argsArray, effectiveLen);
            state.mode = mode;
            state.inputLen = effectiveLen;
            state.args = argsArray.clone();
        } else {
            // same shape
            state.filter.updateParameters(argsArray);
        }

        // Apply filter sequentially
        double out;
        if (mode == 3) {
//            if (state.filter instanceof AdaptiveButterworthFilter) {
//                AdaptiveButterworthFilter f = (AdaptiveButterworthFilter) state.filter;
//                out = (effectiveLen < 3) ? f.filterPadded(limited) : runSequential(state.filter, limited);
//            } else
            if (state.filter instanceof ButterworthIIRFilter) {
                ButterworthIIRFilter f = (ButterworthIIRFilter) state.filter;
                out = (effectiveLen < 3) ? f.filterPadded(limited) : runSequential(state.filter, limited);
            } else {
                out = runSequential(state.filter, limited);
            }
        } else if (mode == 1 && state.filter instanceof CurrentWeightedMovingAverageFilter) {
            out = ((CurrentWeightedMovingAverageFilter) state.filter).filter(limited);
        } else {
            out = runSequential(state.filter, limited);
        }
        return new BasicQualifiedValue(out, QualityCode.Good);
    }

    // Sequential apply: Adapt only the last value.
    private static double runSequential(AbstractFilter f, double[] arr) {
        double tmp = 0.0;
        for (int i = 0, n = arr.length; i < n; i++) tmp = f.filter(arr[i]);
        return tmp;
    }

    // argsList → double[]
    private static double[] parseArgsToDoubleArray(List<?> argsList) throws ExpressionException {
        if (argsList == null || argsList.isEmpty()) return new double[0];
        int n = argsList.size();
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            Object v = argsList.get(i);
            if (v instanceof Number num) {
                out[i] = num.doubleValue();
            } else if (v instanceof String s) {
                try {
                    out[i] = Double.parseDouble(s);
                } catch (NumberFormatException ex) {
                    throw new ExpressionException("Invalid number format in input: " + s, ex);
                }
            } else {
                throw new ExpressionException("Unsupported type: " + v.getClass().getName());
            }
        }
        return out;
    }

    private AbstractFilter createFilter(int mode, double[] args, int len) throws ExpressionException {
        int argsLength = args.length;
        switch (mode) {
            case 1:
                if (argsLength != 1) throw new ExpressionException("Mode 1 (CWMA) requires 1 argument: weight.");
                return new CurrentWeightedMovingAverageFilter(TypeUtilities.toDouble(args[0]));
            case 2:
                if (argsLength != 1) throw new ExpressionException("Mode 2 (WMA) requires 1 argument: windowSize.");
                return new WeightedMovingAverageFilter(TypeUtilities.toInteger(args[0]), len);
            case 3:
                // Butterworth IIR or Adaptive Butterworth IIR
                if (argsLength < 2 || argsLength > 3)
                    throw new ExpressionException("Mode 3 (Butterworth) requires 2 or 3 arguments: [fs, fc, (optional adaptGain)]");
                double fs = TypeUtilities.toDouble(args[0]);
                double fc = TypeUtilities.toDouble(args[1]);
                // Manual Butterworth (static)
                if (argsLength == 2) return new ButterworthIIRFilter(fs, fc);
                else {
                    // Adaptive
                    double adaptGain = TypeUtilities.toDouble(args[2]);
                    return new AdaptiveButterworthFilter(fs, fc, adaptGain);
                }
            case 4:
                if (argsLength < 3)
                    throw new ExpressionException("Mode 4 (Kalman) requires 3 or 6 arguments: [dt, Q, R, (optional alpha, beta, kappa)]");
                int dtMs = TypeUtilities.toInteger(args[0]);
                double Q = TypeUtilities.toDouble(args[1]);
                double R = TypeUtilities.toDouble(args[2]);
                return new KalmanFilter(dtMs, Q, R);

            default: return new PassThroughFilter();
        }
    }

    private static class PassThroughFilter extends AbstractFilter {
        @Override
        protected double applyFilter(double input) {
            double v = FilterUtils.sanitize(input, lastOutput);
            lastOutput = v;
            return v;
        }
        @Override public void updateParameters(double[] args) {}
    }
}
