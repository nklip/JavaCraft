# BDD Module
<sub>[Back to blueprints](../README.md#blueprints)</sub>

This module demonstrates Behavior-Driven Development (BDD) with Cucumber and Spring Boot
using a BMI calculator domain.

## Contents

- [What this module covers](#what-this-module-covers)
- [Test architecture](#test-architecture)
- [How to run tests](#how-to-run-tests)
- [Expected output](#expected-output)
- [IDEA troubleshooting](#idea-troubleshooting)

## What this module covers

`BMI.feature` currently verifies four business scenarios:

1. A person gets BMI from metric input (`kg`/`m`).
2. A person gets BMI from imperial input (`lbs`/`inches`).
3. Several BMI calculations can be checked in one batch table.
4. BMI category messages are validated on boundary values.

In addition to Cucumber scenarios, unit tests cover:

- BMI formula and input validation (`BMIServiceTest`)
- Category boundary behavior, including values with more than two decimals
  normalized before category lookup

## Test architecture

- `CucumberIT` runs feature tests on JUnit Platform with Cucumber.
- `CucumberSpringConfiguration` boots a minimal Spring context for Cucumber steps.
- `BMIStepDefinition` maps Gherkin steps to `BMIService` calls.

## How to run tests

Run all tests (Surefire unit tests + Failsafe Cucumber scenarios):

```bash
mvn -pl bdd verify
```

Run only unit tests:

```bash
mvn -pl bdd -Dtest='*Test' test
```

Run only Cucumber scenarios:

```bash
mvn -pl bdd -Dit.test=CucumberIT verify
```

## Expected output

When tests pass, Maven prints a summary similar to:

```text
Tests run: <n>, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

For Cucumber runs, feature steps are printed in the console and reports are generated at:

- `bdd/target/cucumber-reports/cucumber.html`
- `bdd/target/cucumber-reports/cucumber.json`

Maven test reports are generated at:

- `bdd/target/surefire-reports/`
- `bdd/target/failsafe-reports/`

## IDEA troubleshooting

If a feature file is not linked to step definitions in IntelliJ:  https://youtrack.jetbrains.com/projects/IDEA/issues/IDEA-362929/Cucumber-feature-file-step-appears-as-undefined-in-IntelliJ-despite-the-test-running-successfully.

To fix it: install 'Cucumber Search Indexer' and 'Cucumber for Java' plugins
