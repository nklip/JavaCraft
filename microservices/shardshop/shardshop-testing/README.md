# ShardShop verification

[ShardShop](../README.md) › **Verification** · [Runbook](RUNBOOK.md) · [Architecture](ARCHITECTURE.md)

`shardshop-testing` has no application to start. It runs the verification suites
through Maven: Cucumber scenarios against disposable containers, and opt-in kind
suites that observe the deployed lab.

## Start the suites

| Suite | Command | Needs |
|---|---|---|
| Unit and packaged startup tests | `mvn -B -ntp -f microservices/shardshop/pom.xml clean verify` | JDK 25 and Maven |
| Testcontainers scenarios | `mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pintegration clean verify` | Docker and `helm` |
| kind suites | `mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pkind verify -Dshardshop.kind.snapshot="$snapshot"` | A running lab and a [verification snapshot](RUNBOOK.md#kind-verification-snapshot) |

Disruptive drills require explicit selection; the [runbook](RUNBOOK.md) lists each
suite, drill and capacity measurement.

## Results

| Result | Location |
|---|---|
| Testcontainers Cucumber reports | `shardshop-testing/target/cucumber-reports` |
| Application logs of the scenarios | `shardshop-testing/target/scenario-runs` |
| kind Cucumber reports | `shardshop-testing/target/kind-reports/` |
| Unit and integration coverage | `target/site/jacoco` and `target/site/jacoco-it` of each module |
| Dated evidence | [CHANGELOG](../CHANGELOG.md) and [`evidence/`](evidence/) |
