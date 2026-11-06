package dev.nklip.javacraft.shardshop.testing.cluster;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;

import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME;

/** Lifecycle gates against an explicitly selected local or AWS deployment; selected only by the opt-in kind profile. */
@Suite(failIfNoTests = true)
@IncludeEngines("cucumber")
@SelectPackages("cluster-features")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "dev.nklip.javacraft.shardshop.testing.cluster,dev.nklip.javacraft.shardshop.testing.kind,dev.nklip.javacraft.shardshop.testing.aws")
@ConfigurationParameter(key = PLUGIN_PROPERTY_NAME,
        value = "pretty, html:target/cluster-reports/kind.html, json:target/cluster-reports/kind.json")
public class ClusterIT {
}
