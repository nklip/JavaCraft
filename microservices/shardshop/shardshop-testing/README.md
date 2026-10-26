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

Step **6.3 is Done**, qualified on 2026-10-26: coordinated backup, isolated
restore/explicit CDC recovery, final source checks, latest allocator export and
restoration audit passed. The initial reader-gate failure remains recorded;
the focused two-case source rerun passed with unchanged thresholds. See
[evidence](../CHANGELOG.md#step-63).
`kind-backup`, `kind-restore` and the standalone `kind-backup-allocator` history
export are separately
opt-in and are not selected by `kind-drill` or the default kind suite. See the [workflow and private-artifact requirements](RUNBOOK.md#coordinated-backup-and-isolated-restore).
Step 6.2 remains qualified. Step 6.4 is Done; see [evidence](../CHANGELOG.md#step-64).

## Older-backup audit and selected repair

Step **6.4 is Done**; 60 focused checks and live older-backup recovery qualification pass. Separate opt-in `kind-recovery-record`, `kind-recovery-audit`,
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
