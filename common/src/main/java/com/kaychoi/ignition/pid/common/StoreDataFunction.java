package com.kaychoi.ignition.pid.common;

import com.inductiveautomation.ignition.common.TypeUtilities;
import com.inductiveautomation.ignition.common.expressions.Expression;
import com.inductiveautomation.ignition.common.expressions.ExpressionException;
import com.inductiveautomation.ignition.common.expressions.functions.AbstractFunction;
import com.inductiveautomation.ignition.common.model.values.BasicQualifiedValue;
import com.inductiveautomation.ignition.common.model.values.QualifiedValue;
import com.inductiveautomation.ignition.common.model.values.QualityCode;

import java.util.List;
import java.util.UUID;

/**
 * Ignition Expression Function:
 * storeData(enable:boolean, size:int, input:double, uniqueId:string)
 *
 * Maintains a time-limited buffer of recent numeric values.
 * Independent from CustomFilterFunction — each uses its own UUID namespace.
 */
public class StoreDataFunction extends AbstractFunction {

    private static final TimedObjectManager<StoreData> STORE_MANAGER =
            new TimedObjectManager<>(60 * 60 * 1000L, 10 * 60 * 1000L, "StoreData-Cleanup");

    @Override
    public Class<?> getType() { return List.class; }

    @Override
    protected String getFunctionDisplayName() { return "storeData"; }

    @Override
    public String getArgDocString() { return "enable:boolean, size:int, input:double, uniqueId:string"; }

    @Override
    protected boolean validateNumArgs(int num) { return num == 4; }

    @Override
    public QualifiedValue execute(Expression[] expressions) throws ExpressionException {
        boolean enable = TypeUtilities.toBool(expressions[0].execute().getValue());
        int size = TypeUtilities.toInteger(expressions[1].execute().getValue());
        double input = TypeUtilities.toDouble(expressions[2].execute().getValue());
        QualityCode quality = expressions[2].execute().getQuality();
        String uid = String.valueOf(expressions[3].execute().getValue());

        // Convert to global UUID (namespace = storeFilterData)
        UUID uuid = UUIDKeyManager.getOrCreateUUID("storeFilterData", uid);

        if (!enable || size <= 0) {
            STORE_MANAGER.remove(uuid, null);
            UUIDKeyManager.remove("storeFilterData", uid);
            return new BasicQualifiedValue(List.of(input), quality);
        }

        // Get or create buffer object
        StoreData buffer = STORE_MANAGER.getOrCreate(uuid, () -> new StoreData(false));
        List<Double> result = buffer.addInput(true, size, input);

        return new BasicQualifiedValue(result, quality);
    }
}
