# BDD Module

[Blueprints](../README.md) › **BDD Module** · [Repository test coverage](../../README.md#4-test-coverage)

This module demonstrates Behavior-Driven Development (BDD) with Cucumber and Spring Boot
using a BMI calculator domain.

## Contents

1. [What this module covers](#1-what-this-module-covers)
2. [Test architecture](#2-test-architecture)
3. [How to run tests](#3-how-to-run-tests)
4. [Expected output](#4-expected-output)
5. [IDEA troubleshooting](#5-idea-troubleshooting)

## 1. What this module covers

<sub>[Back to Contents](#contents)</sub>

The examples in [BMI.feature](src/test/resources/features/BMI.feature) cover three behaviors.

- A person weighing 75 kg at 1.75 m gets a BMI of 24.49 in the normal range.
- A person weighing 150 lb at 65 inches gets a BMI of 24.96.
- BMI values at category boundaries produce the expected category messages.

[BMIServiceTest](src/test/java/dev/nklip/javacraft/bdd/service/BMIServiceTest.java) covers input validation,
both BMI formulas, half-even rounding, and category boundaries for values with more than two decimal places.

## 2. Test architecture

<sub>[Back to Contents](#contents)</sub>

- `CucumberIT` runs feature tests on JUnit Platform with Cucumber.
- `CucumberSpringConfiguration` boots a minimal Spring context for Cucumber steps.
- `BMIStepDefinitions` maps Gherkin steps to `BMIService` calls.

## 3. How to run tests

<sub>[Back to Contents](#contents)</sub>

Run these commands from the repository root. `verify` runs unit tests with Surefire
and Cucumber scenarios with Failsafe.

```bash
mvn -pl blueprints/bdd verify
```

The `test` phase runs only the unit tests.

```bash
mvn -pl blueprints/bdd test
```

To run only Cucumber, compile the test classes and invoke Failsafe directly.

```bash
mvn -pl blueprints/bdd test-compile failsafe:integration-test failsafe:verify -Dit.test=CucumberIT
```

## 4. Expected output

<sub>[Back to Contents](#contents)</sub>

When tests pass, Maven prints a summary similar to this one.

```text
Tests run: <n>, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

For Cucumber runs, Maven prints feature steps in the console and writes these reports.

- `blueprints/bdd/target/cucumber-reports/cucumber.html`
- `blueprints/bdd/target/cucumber-reports/cucumber.json`

The Maven test plugins write their reports to these directories.

- Surefire unit tests in `blueprints/bdd/target/surefire-reports/`
- Failsafe Cucumber scenarios in `blueprints/bdd/target/failsafe-reports/`

## 5. IDEA troubleshooting

<sub>[Back to Contents](#contents)</sub>

If IntelliJ cannot link feature steps to their definitions, install the **Cucumber Search Indexer** and **Cucumber for Java** plugins. [JetBrains issue IDEA-362929](https://youtrack.jetbrains.com/projects/IDEA/issues/IDEA-362929/Cucumber-feature-file-step-appears-as-undefined-in-IntelliJ-despite-the-test-running-successfully)
