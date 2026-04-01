package dev.nklip.javacraft.bdd.cucumber;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;

import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME;

// issue: https://youtrack.jetbrains.com/projects/IDEA/issues/IDEA-362929/Cucumber-feature-file-step-appears-as-undefined-in-IntelliJ-despite-the-test-running-successfully.
// install 'Cucumber Search Indexer' and 'Cucumber for Java' plugins to avoid it
@Suite
@IncludeEngines("cucumber")
@SelectPackages("features")
@ConfigurationParameter(key = PLUGIN_PROPERTY_NAME,
        value = "pretty, html:target/cucumber-reports/cucumber.html, json:target/cucumber-reports/cucumber.json")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "dev.nklip.javacraft.bdd.cucumber")
public class CucumberRunner {
}
