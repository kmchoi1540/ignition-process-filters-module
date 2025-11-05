package com.kaychoi.ignition.pid.common.filter;

import com.inductiveautomation.ignition.common.TypeUtilities;
import com.inductiveautomation.ignition.common.document.Document;
import com.inductiveautomation.ignition.common.document.DocumentArray;
import com.inductiveautomation.ignition.common.document.DocumentElement;
import com.inductiveautomation.ignition.common.document.DocumentNull;
import com.inductiveautomation.ignition.common.expressions.ExpressionException;
import com.inductiveautomation.ignition.common.script.adapters.PyDocumentObjectAdapter;
import org.python.core.Py;
import org.python.core.PyArray;
import org.python.core.PyObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility class to normalize various Ignition / Jython / Document values
 * into a Java List or primitive double[].
 *
 * This is necessary because expression functions may receive:
 * - Java List
 * - double[] / Double[]
 * - Jython pyList / PyArray
 * - Ignition Document / PyDocumentObjectAdapter (with "value" field)
 * - Single values (which then wrapped into a single-element list)
 *
 * Also supports "forward fill" behavior:
 * - If encounters a null/DocumentNull in the middle, this reuses the last valid value
 * - If the very first value is invalid, throw an ExpressionException
 */
public class FilterUtils {

    /* ==============================================================
     *  ⬛ NUMERIC HELPERS
     * ============================================================== */

    public static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Sanitize a numeric value.
     * - NaN / Infinite → 0.0
     * - Values above ±1e10 are clamped.
     */
    public static double sanitize(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0.0;
        if (v > 1e10) return 1e10;
        if (v < -1e10) return -1e10;
        return v;
    }

    /**
     * Sanitize a numeric value with optional fallback to last output (legacy-compatible).
     * - If NaN or Infinite → use lastOutput (if not null), else 0.0
     * - Otherwise → clamp to ±1e10
     */
    public static double sanitize(double v, Double lastOutput) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return (lastOutput != null) ? lastOutput : 0.0;
        }
        if (v > 1e10) return 1e10;
        if (v < -1e10) return -1e10;
        return v;
    }

    public static double sanitizeAndClamp(double v, double min, double max) {
        return clamp(sanitize(v), min, max);
    }

    public static double normalize(double value, double min, double max) {
        if (max - min == 0) return 0;
        return (value - min) / (max - min);
    }

    public static double mean(double[] arr, int endIndex, Double lastOutput) {
        if (arr == null || arr.length == 0 || endIndex <= 0) return 0.0;
        double sum = 0.0;
        int count = Math.min(endIndex, arr.length);
        for (int i = 0; i < count; i++) {
            sum += sanitize(arr[i], lastOutput);
        }
        return sum / count;
    }

    public static double stddev(double[] arr, Double lastOutput) {
        if (arr == null || arr.length < 2) return 0.0;
        double m = mean(arr, arr.length - 1, lastOutput);
        double s = 0;
        for (double v : arr) s += (v - m) * (v - m);
        return Math.sqrt(s / (arr.length - 1));
    }

    /**
     * Convert an arbitrary structure to a double array, with forward-fill for invalid values.
     *
     * @param rawInputs incoming object (can be list, document, single number, etc.)
     * @return primitive double array
     * @throws ExpressionException when there is no valid value to forward-fill with
     */
    public static double[] toDoubleArray(Object rawInputs) throws ExpressionException {
        List<Object> items = normalizeToList(rawInputs);

        if (items == null || items.isEmpty()) {
            // allow empty array, do not crash the entire expression
            return new double[0];
        }

        List<Double> result = new ArrayList<>();
        Double lastValid = null;

        for (Object item : items) {
            Double value = tryParseDouble(item);
            if (value != null) {
                lastValid = value;
                result.add(value);
            } else if (lastValid != null) {
                // Forward-fill if possible
                result.add(lastValid);
            } else {
                // First item is invalid → cannot forward-fill
                throw new ExpressionException(
                        "Invalid value with no previous valid value to fall back on: " + item
                );
            }
        }

        // Convert to primitive array
        double[] output = new double[result.size()];
        for (int i = 0; i < result.size(); i++) {
            output[i] = result.get(i);
        }
        return output;
    }

    /**
     * Tries to interpret a single object as Double.
     * Returns null if it's not a valid numeric representation.
     */
    private static Double tryParseDouble(Object obj) {
        if (obj == null) return null;

        if (obj instanceof Number) {
            return ((Number) obj).doubleValue();
        }

        String str = obj.toString().trim();
        if (str.isEmpty()
                || str.equalsIgnoreCase("null")
                || str.equalsIgnoreCase("none")
                || str.equalsIgnoreCase("nan")) {
            return null;
        }

        try {
            return Double.valueOf(str);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Normalizes any supported args into a List<Object>.
     *
     * @param raw input object
     * @return List of items, never null (except when raw is null)
     */
    @SuppressWarnings("unchecked")
    static List<Object> normalizeToList(Object raw) throws ExpressionException {
        if (raw == null || raw == "") return null;

        // Already a Java List
        if (raw instanceof List<?>) {
            return (List<Object>) raw;
        }

        // Primitive double[]
        if (raw instanceof double[]) {
            double[] arr = (double[]) raw;
            List<Object> list = new ArrayList<>(arr.length);
            for (double d : arr) list.add(d);
            return list;
        }

        // Boxed Double[]
        if (raw instanceof Double[]) {
            Double[] arr = (Double[]) raw;
            List<Object> list = new ArrayList<>(arr.length);
            for (Double d : arr) list.add(d);
            return list;
        }

        // Jython PyArray
        if (raw instanceof PyArray) {
            PyArray pa = (PyArray) raw;
            List<Object> list = new ArrayList<>();
            for (int i = 0; i < pa.__len__(); i++) {
                PyObject py = pa.__getitem__(i);
                list.add(TypeUtilities.pyToJava(py));
            }
            return list;
        }

        // General PyObject (pyList, etc.)
        if (raw instanceof PyObject) {
            PyObject pyObj = (PyObject) raw;
            try {
                List<?> javaList = (List<?>) pyObj.__tojava__(List.class);
                return new ArrayList<>(javaList);
            } catch (Exception ex) {
                Object javaObj = TypeUtilities.pyToJava(pyObj);
                if (javaObj instanceof List<?>) {
                    return new ArrayList<>((List<?>) javaObj);
                }
            }
        }

        // Ignition Document (e.g. {"value":[1,2,3]})
        if (raw instanceof Document) {
            Document doc = (Document) raw;
            Object element = doc.get("value");

            if (element instanceof List<?>) {
                return new ArrayList<>((List<?>) element);
            } else if (element instanceof DocumentArray) {
                DocumentArray array = (DocumentArray) element;
                List<Object> list = new ArrayList<>(array.size());
                Double lastValid = null;

                for (DocumentElement de : array) {
                    if (de == DocumentNull.INSTANCE) {
                        if (lastValid == null) {
                            throw new ExpressionException("customFilter: DocumentNull found with no valid initial value.");
                        }
                        list.add(lastValid);
                    } else {
                        double val = de.getAsDouble();
                        lastValid = val;
                        list.add(val);
                    }
                }
                return list;
            } else if (element == null) {
                throw new ExpressionException("customFilter: 'value' key is missing or null in Document.");
            } else {
                throw new ExpressionException("customFilter: Unexpected value type inside Document: " + element.getClass().getName());
            }
        }

        // PyDocumentObjectAdapter (Scripting Document wrapper)
        if (raw instanceof PyDocumentObjectAdapter) {
            PyDocumentObjectAdapter doc = (PyDocumentObjectAdapter) raw;
            PyObject pyValue = doc.get(Py.newString("value"), Py.None);
            Object value = TypeUtilities.pyToJava(pyValue);
            return normalizeToList(value);
        }

        // Fallback: wrap a single object
        List<Object> fallback = new ArrayList<>(1);
        fallback.add(raw);
        return fallback;

    }

}
