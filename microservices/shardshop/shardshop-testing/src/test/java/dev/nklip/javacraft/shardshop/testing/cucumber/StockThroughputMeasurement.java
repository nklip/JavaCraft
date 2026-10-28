package dev.nklip.javacraft.shardshop.testing.cucumber;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME;

/** Explicitly selected with Failsafe -Dit.test=StockThroughputMeasurement. */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("stock-throughput")
@ConfigurationParameter(key = PLUGIN_PROPERTY_NAME,
        value = "pretty, json:target/stock-throughput-reports/cucumber.json")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME,
        value = "dev.nklip.javacraft.shardshop.testing.cucumber")
public class StockThroughputMeasurement {
}
