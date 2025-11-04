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

import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Expression Function:
 * customFilter(enable:boolean, mode:int, size:int, args:list, inputs:list, [uniqueId:string])
 *
 * Modes:
 * 1 - Current Weighted Moving Average
 * 2 - Weighted Moving Average
 * 3 - Butterworth IIR
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
    @Override public String getArgDocString() { return "enable:boolean, mode:int, size:int, args:list, inputs:list, [uniqueId:string]"; }
    @Override protected boolean validateNumArgs(int n) { return n == 5 || n == 6; }

    @Override
    public QualifiedValue execute(Expression[] exprs) throws ExpressionException {
        try {
            return doExecute(exprs);
        } catch (ExpressionException e) {
            LOGGER.log(Level.WARNING, "customFilter: Expression error - " + e.getMessage(), e);
            return new BasicQualifiedValue(0.0, QualityCode.Bad);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "customFilter: Runtime error - " + e.getMessage(), e);
            return new BasicQualifiedValue(0.0, QualityCode.Bad);
        }
    }

    private QualifiedValue doExecute(Expression[] e) throws ExpressionException {
        boolean enable = TypeUtilities.toBool(e[0].execute().getValue());
        int mode = TypeUtilities.toInteger(e[1].execute().getValue());
        int size = TypeUtilities.toInteger(e[2].execute().getValue());
        Object rawArgs = e[3].execute().getValue();
        Object rawInputs = e[4].execute().getValue();
        boolean hasId = (e.length == 6);
        String uniqueId = hasId ? String.valueOf(e[5].execute().getValue()) : UUID.randomUUID().toString();

        UUID uuid = UUIDKeyManager.getOrCreateUUID("customFilter", uniqueId);

        List<?> argsList = FilterUtils.normalizeToList(rawArgs);
        double[] inputs = FilterUtils.toDoubleArray(rawInputs);

        int effectiveLen = Math.min(size, inputs.length);
        double[] limited;
        if (size > 0 && inputs.length > size) {
            limited = new double[effectiveLen];
            System.arraycopy(inputs, inputs.length - effectiveLen, limited, 0, effectiveLen);
        } else {
            limited = inputs;
        }

        double[] argsArray = argsList.stream()
                .mapToDouble(v -> ((Number) v).doubleValue())
                .toArray();

        if (!enable) {
            FILTER_MANAGER.remove(uuid, null);
            UUIDKeyManager.remove("customFilter", uniqueId);
            double last = limited.length > 0 ? limited[limited.length - 1] : 0.0;
            return new BasicQualifiedValue(last, QualityCode.Good);
        }

        // Argument validation
        if ((mode == 1 || mode == 2) && argsList.size() != 1)
            throw new ExpressionException("Mode " + mode + " requires 1 argument.");
        if (mode == 3 && !(argsList.size() == 2 || argsList.size() == 3))
            throw new ExpressionException("Mode 3 requires 2 or 3 arguments: [fs, fc, (optional adaptGain)].");

        // Create or retrieve filter state
        FilterState state = FILTER_MANAGER.getOrCreate(uuid, () -> {
            FilterState fs = new FilterState();
            try {
                fs.filter = createFilter(mode, argsList, limited.length);
            } catch (ExpressionException ex) {
                throw new RuntimeException(ex);
            }
            fs.mode = mode;
            fs.args = argsArray;
            fs.inputLen = limited.length;
            return fs;
        });

        // Detect configuration changes
        boolean rebuild = (state.mode != mode) || (state.inputLen != limited.length);

        // For mode 3, switching between ButterworthIIR and Adaptive (args length 2↔3) requires rebuild
        if (mode == 3 && state.args != null && state.args.length != argsArray.length) {
            rebuild = true;
        }

        if (rebuild) {
            state.filter = createFilter(mode, argsList, limited.length);
        } else {
            state.filter.updateParameters(argsArray);
        }

        // Update metadata
        state.mode = mode;
        state.args = argsArray;
        state.inputLen = limited.length;

        // Apply filter sequentially
        double out;

        if (mode == 3) {
            if (state.filter instanceof AdaptiveButterworthFilter) {
                AdaptiveButterworthFilter f = (AdaptiveButterworthFilter) state.filter;
                out = (limited.length < 3) ? f.filterPadded(limited) : f.filter(limited);
            } else if (state.filter instanceof ButterworthIIRFilter) {
                ButterworthIIRFilter f = (ButterworthIIRFilter) state.filter;
                if (limited.length < 3) {
                    out = f.filterPadded(limited);
                } else {
                    double tmp = 0.0;
                    for (double v : limited) tmp = state.filter.filter(v);
                    out = tmp;
                }
            } else {
                double tmp = 0.0;
                for (double v : limited) tmp = state.filter.filter(v);
                out = tmp;
            }
        } else if (mode == 1 && state.filter instanceof CurrentWeightedMovingAverageFilter) {
            out = ((CurrentWeightedMovingAverageFilter) state.filter).filter(limited);
        } else {
            double tmp = 0.0;
            for (double v : limited) tmp = state.filter.filter(v);
            out = tmp;
        }
        return new BasicQualifiedValue(out, QualityCode.Good);
    }

    private AbstractFilter createFilter(int mode, List<?> args, int len) throws ExpressionException {
        switch (mode) {
            case 1:
                if (args.size() != 1)
                    throw new ExpressionException("Mode 1 (CWMA) requires 1 argument: weight.");
                return new CurrentWeightedMovingAverageFilter(((Number) args.get(0)).doubleValue());
            case 2:
                if (args.size() != 1)
                    throw new ExpressionException("Mode 2 (WMA) requires 1 argument: windowSize.");
                return new WeightedMovingAverageFilter(((Number) args.get(0)).intValue(), len);
            case 3:
                // Butterworth IIR or Adaptive Butterworth IIR
                if (args.size() < 2 || args.size() > 3)
                    throw new ExpressionException("Mode 3 (Butterworth) requires 2 or 3 arguments: [fs, fc, (optional adaptGain)]");
                double fs = ((Number) args.get(0)).doubleValue();
                double fc = ((Number) args.get(1)).doubleValue();
                if (args.size() == 2) {
                    // Manual Butterworth (static)
                    return new ButterworthIIRFilter(fs, fc);
                } else {
                    // Adaptive
                    double adaptGain = ((Number) args.get(2)).doubleValue();
                    return new AdaptiveButterworthFilter(fs, fc, adaptGain);
                }

            default: return new PassThroughFilter();
        }
    }

    private static class PassThroughFilter extends AbstractFilter {
        @Override
        protected double applyFilter(double input) {
            return FilterUtils.sanitize(input, lastOutput);
        }
        @Override public void updateParameters(double[] args) {}
        @Override public void reset() {}
    }
}
