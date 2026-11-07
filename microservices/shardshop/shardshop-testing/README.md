# ShardShop verification

[ShardShop](../README.md) › **Verification** · [Runbook](RUNBOOK.md) · [Architecture](ARCHITECTURE.md)

`shardshop-testing` has no application to start. It runs the verification suites
through Maven: Cucumber scenarios against disposable containers, and opt-in cluster
suites that observe a deployed lab: the kind lab or EKS.

## Contents
1. [Start the suites](#1-start-the-suites)
2. [Packages](#2-packages)
3. [Coordinated backup and isolated restore](#3-coordinated-backup-and-isolated-restore)
4. [Older-backup audit and selected repair](#4-older-backup-audit-and-selected-repair)
5. [JavaScript page checks](#5-javascript-page-checks)
6. [Results](#6-results)

## 1. Start the suites
<sub>[Back to Contents](#contents)</sub>

| Suite | Command | Needs |
|---|---|---|
| Unit and packaged startup tests | `mvn -B -ntp -f microservices/shardshop/pom.xml clean verify` | JDK 25, Maven and Node.js on `PATH` |
| Testcontainers scenarios | `mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pintegration clean verify` | Docker and `helm` |
| Cluster suites | `mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pcluster verify -Dshardshop.cluster.snapshot="$snapshot"` | A running lab and a [verification snapshot](RUNBOOK.md#3-cluster-verification-snapshot) |

Disruptive drills require explicit selection; the [runbook](RUNBOOK.md) lists each
suite, drill and capacity measurement.

## 2. Packages
<sub>[Back to Contents](#contents)</sub>

The test classes are in `dev.nklip.javacraft.shardshop.testing`. Each package
matches where its tests run:

| Package | Runs | Contents |
|---|---|---|
| `browser` | `test` phase, Node.js | The checks of the page scripts |
| `infra` | `test` phase | The render tests of the charts and the Helmfile, and the tests of the lifecycle scripts |
| `support` | — | The helpers of more than one suite: JSON, raw HTTP, packaged applications, the broker, the catalog databases and the webpage routes |
| `integration` | `-Pintegration` | The Testcontainers suite `CucumberIT` with `config`, `world` and `step`, and the opt-in stage measurements in `measurement` |
| `cluster` | `-Pcluster`, on the kind lab and on EKS | The suite `ClusterIT` and the shared cluster helpers. `gate` has the lifecycle and release gates, `drill` the drills, `recovery` the backup, restore and recovery checks, and `capacity` the order capacity check |
| `kind` | `-Pcluster`, only on the kind lab | The checks of the image store of the kind nodes and of the retained-upgrade evidence |
| `aws` | `-Pcluster`, only on EKS | The checks of the Argo CD delivery, the S3 durability, the gates image and the webpages |

Shared classes do not depend on `kind` or `aws`, and `integration` and `cluster`
depend only on `support`. The tags of the checks that run on both deployments start
with `cluster-`. The tags of the checks that run only on kind start with `kind-`, and
the tags of the EKS checks start with `eks-`.

## 3. Coordinated backup and isolated restore
<sub>[Back to Contents](#contents)</sub>

`cluster-backup`, `cluster-restore` and the standalone `cluster-backup-allocator` history
export are opt-in. `cluster-drill` and the default cluster suite do not select them. See
the [workflow and private-artifact requirements](RUNBOOK.md#8-coordinated-backup-and-isolated-restore).

## 4. Older-backup audit and selected repair
<sub>[Back to Contents](#contents)</sub>

The opt-in `cluster-recovery-record`, `cluster-recovery-audit`,
`cluster-recovery-repair` and `cluster-recovery-drill` operate only on fresh isolated
targets and retain private review evidence. The focused `cluster-recovery-schema`
checks native schema restoration. All five are excluded from `cluster-drill`.
See the [recovery workflow](RUNBOOK.md#9-older-backup-audit-and-selected-repair).

## 5. JavaScript page checks
<sub>[Back to Contents](#contents)</sub>

The default Maven test suite runs the product, order and workload page harnesses
through Node.js; no browser installation is needed. Node.js on `PATH` is mandatory:
a missing executable or failing harness fails Maven. See the
[focused checks](RUNBOOK.md#1-javascript-page-checks) for commands and prerequisites.

## 6. Results
<sub>[Back to Contents](#contents)</sub>

| Result | Location |
|---|---|
| Testcontainers Cucumber reports | `shardshop-testing/target/cucumber-reports` |
| Application logs of the scenarios | `shardshop-testing/target/scenario-runs` |
| Cluster Cucumber reports | `shardshop-testing/target/cluster-reports/` |
| Unit and integration coverage | `target/site/jacoco` and `target/site/jacoco-it` of each module |
| Dated evidence | [CHANGELOG](../CHANGELOG.md) and [`evidence/`](evidence/) |
