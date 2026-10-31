# ShardShop verification

[ShardShop](../README.md) › **Verification** · [Runbook](RUNBOOK.md) · [Architecture](ARCHITECTURE.md)

`shardshop-testing` has no application to start. It runs the verification suites
through Maven: Cucumber scenarios against disposable containers, and opt-in kind
suites that observe the deployed lab.

## Start the suites

| Suite | Command | Needs |
|---|---|---|
| Unit and packaged startup tests | `mvn -B -ntp -f microservices/shardshop/pom.xml clean verify` | JDK 25, Maven and Node.js on `PATH` |
| Testcontainers scenarios | `mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pintegration clean verify` | Docker and `helm` |
| kind suites | `mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pkind verify -Dshardshop.kind.snapshot="$snapshot"` | A running lab and a [verification snapshot](RUNBOOK.md#kind-verification-snapshot) |

Disruptive drills require explicit selection; the [runbook](RUNBOOK.md) lists each
suite, drill and capacity measurement.

## Coordinated backup and isolated restore

`kind-backup`, `kind-restore` and the standalone `kind-backup-allocator` history
export are opt-in. `kind-drill` and the default kind suite do not select them. See
the [workflow and private-artifact requirements](RUNBOOK.md#coordinated-backup-and-isolated-restore).

## Older-backup audit and selected repair

The opt-in `kind-recovery-record`, `kind-recovery-audit`,
`kind-recovery-repair` and `kind-recovery-drill` operate only on fresh isolated
targets and retain private review evidence. The focused `kind-recovery-schema`
checks native schema restoration. All five are excluded from `kind-drill`.
See the [recovery workflow](RUNBOOK.md#older-backup-audit-and-selected-repair).

## JavaScript page checks

The default Maven test suite runs the product, order and workload page harnesses
through Node.js; no browser installation is needed. Node.js on `PATH` is mandatory:
a missing executable or failing harness fails Maven. See the
[focused checks](RUNBOOK.md#javascript-page-checks) for commands and prerequisites.

## Results

| Result | Location |
|---|---|
| Testcontainers Cucumber reports | `shardshop-testing/target/cucumber-reports` |
| Application logs of the scenarios | `shardshop-testing/target/scenario-runs` |
| kind Cucumber reports | `shardshop-testing/target/kind-reports/` |
| Unit and integration coverage | `target/site/jacoco` and `target/site/jacoco-it` of each module |
| Dated evidence | [CHANGELOG](../CHANGELOG.md) and [`evidence/`](evidence/) |
