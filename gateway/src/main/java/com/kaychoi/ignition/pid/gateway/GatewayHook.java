package com.kaychoi.ignition.pid.gateway;

import com.kaychoi.ignition.pid.common.filter.CustomFilterFunction;
import com.kaychoi.ignition.pid.common.StoreDataFunction;
import com.inductiveautomation.ignition.common.expressions.ExpressionFunctionManager;
import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.gateway.model.AbstractGatewayModuleHook;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gateway module hook configured to add the expression, allowing the expression to be used in gateway expression tags.
 */
public class GatewayHook extends AbstractGatewayModuleHook {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Override
    public void setup(GatewayContext gatewayContext) {

    }

    @Override
    public void startup(LicenseState licenseState) {

    }

    @Override
    public void shutdown() {

    }

    @Override
    public void configureFunctionFactory(ExpressionFunctionManager factory) {
        factory.getCategories().add("ProcessFilters");
        factory.addFunction("customFilter", "ProcessFilters", new CustomFilterFunction());
        factory.addFunction("storeData", "ProcessFilters", new StoreDataFunction());
        super.configureFunctionFactory(factory);
    }

}