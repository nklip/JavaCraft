package dev.nklip.javacraft.shardshop.testing.kind;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;

import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME;

/** Lifecycle gates against an explicitly selected local lab; selected only by the opt-in kind profile. */
@Suite(failIfNoTests = true)
@IncludeEngines("cucumber")
@SelectPackages("kind-features")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "dev.nklip.javacraft.shardshop.testing.kind")
@ConfigurationParameter(key = PLUGIN_PROPERTY_NAME,
        value = "pretty, html:target/kind-reports/kind.html, json:target/kind-reports/kind.json")
public class KindIT {
}
