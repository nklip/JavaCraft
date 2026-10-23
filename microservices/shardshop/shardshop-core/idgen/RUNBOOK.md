# Identifier generation runbook

[ShardShop](../../README.md) › [Core](../README.md) › **Identifier generation runbook** · [Architecture](ARCHITECTURE.md)

## Bounded Snowflake generation

Product and order each inject one eager, process-scoped `IdGenerator` through the
shared CDI producer in `shardshop-idgen` (`shardshop-core/idgen`). That library
owns the generator, checked time source, exception and their unit tests in
`dev.nklip.javacraft.shardshop.idgen`, and declares the Snowflake dependency.
Its SmallRye/CDI wiring and injection tests live in
`dev.nklip.javacraft.shardshop.idgen.config`. The generator domain classes have no
framework imports. Common, sharding, ledger and workloads contain neither
generator classes nor Snowflake.
The generator uses the pinned Snowflake 0.0.2 library with epoch `2026-01-01T00:00:00Z`,
a 41/10/12-bit layout and
`THROW_EXCEPTION` sequence overflow. A checked `MonotonicTimeSource` validates
the exact returned millisecond tick before the library masks it; negative ticks
and ticks at or above `2^41` fail. The last valid millisecond is
`2095-09-07T15:47:35.551Z`.

`nextId()` returns a positive `long`, or throws the shared
`IdGenerationUnavailableException` with code `ID_GENERATION_UNAVAILABLE`.
Sequence exhaustion (4,096 IDs in one millisecond), backwards time, clock-source
failure and timestamp overflow never return an ID or trigger a spin/retry loop.
HTTP providers map this exception to `503 ID_GENERATION_UNAVAILABLE` and abort their
transaction; product and order do this.

A production start requires a generator ID in `1..1023`; there is no production
default. Generator 0 remains reserved for historical illustrative fixtures; no
runtime creation path or workload emits generator-0 IDs. A deployed JVM reserves its
ID through the registry ([generator allocation](#generator-allocation-at-jvm-startup)).
The fixed `shardshop.id.generator-id`, also configurable through
`SHARDSHOP_ID_GENERATOR_ID`, is only for disposable tests without retained data. It
does not reserve or persist an ID, and a packaged JVM in a Kubernetes pod refuses it.

Controlled-clock tests exercise concurrency, sequence exhaustion/recovery, source
failures, backwards time, epoch/layout compatibility, exact timestamp limits and
the maximum positive `long`. CDI tests check shared instance identity; packaged
startup tests cover system-property and environment-variable configuration,
including missing, empty, malformed and out-of-range live IDs. Run the shared
ID-generation library's unit and CDI suite with
`mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-core/idgen -am clean verify`.
Run routing's unit and CDI suite with
`mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-core/sharding -am clean verify`.
Run both owners and their shared libraries:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml \
  -pl shardshop-product,shardshop-order -am clean verify
```

The [six-application audit](../../RUNBOOK.md#dependency-and-test-validation) produces the
dependency/SBOM reports for the ownership checks.

## Generator allocation at JVM startup

Product and order reserve a fresh generator ID in their own JVM at every start, also
after a container restart in the same pod. The eager CDI producer of the
generator in `shardshop-idgen` does this before the generator exists:

1. It reads `shardshop-snowflake-generators` through the Kubernetes API with Apache
   HttpClient 5. The request has the token of the pod's service account. The client
   trusts only the CA of the service account volume, and the server certificate must
   name the host of the URL.
2. It sends one JSON patch that tests the UID, the resource version and
   `data.highWaterMark` of the registry, and replaces the counter.
3. Only an acknowledged reservation becomes the generator ID. The JVM then logs
   `Reserved generator ID <n> for this JVM start.` at INFO.

No kubectl binary, launcher process, init container or sidecar takes part.
Higher-priority configuration cannot substitute an ID for the reservation: a fixed
`shardshop.id.generator-id` together with a registry UID stops the start. The
producer has a later startup priority than the other startup beans, and a bean
that injects the generator creates it at once. Thus product and order create their
stores and checked service settings at an earlier priority than their services:
the checks of the routing snapshot, the connections, the pools and the service
settings come first, and a local configuration error stops the start before a
reservation. The packaged startup tests set a registry UID without a CA file and
show this for every setting.

| Property | Environment variable | Default and rule |
|---|---|---|
| `shardshop.id.registry.uid` | `SHARDSHOP_ID_REGISTRY_UID` | None. A value selects the reservation. The product Deployment takes it from the `registryUid` key of the immutable `shardshop-generator-identity` ConfigMap |
| `shardshop.id.registry.url` | `SHARDSHOP_ID_REGISTRY_URL` | `https://kubernetes.default.svc`. Only `https`, with a host and an optional port, and no path, query or user information |
| `shardshop.id.registry.namespace` | `SHARDSHOP_ID_REGISTRY_NAMESPACE` | `shardshop` |
| `shardshop.id.registry.token-file` | `SHARDSHOP_ID_REGISTRY_TOKEN_FILE` | `/var/run/secrets/kubernetes.io/serviceaccount/token`, read again for each request |
| `shardshop.id.registry.ca-file` | `SHARDSHOP_ID_REGISTRY_CA_FILE` | `/var/run/secrets/kubernetes.io/serviceaccount/ca.crt` |
| `shardshop.id.generator-id` | `SHARDSHOP_ID_GENERATOR_ID` | None. A fixed ID for disposable tests outside Kubernetes. A packaged JVM in a pod (`KUBERNETES_SERVICE_HOST` is set) refuses it |

Allocation has a 30-second budget, requests of at most five seconds, and retry
backoff from 25 to 500 milliseconds. A conflict, a policy denial, a server error or a
lost response burns its candidate: the next attempt advances beyond both the
observed counter and the uncertain candidate. A late acknowledgement cannot start the
application. A refused PATCH (401, 403 or 404) stops the start at once.
Missing, malformed, inaccessible, replaced or exhausted state fails startup.
The diagnostics identify exhaustion, stale identity, allocation deadlines, the API
client configuration, and read and update failures of the registry, with their next
checks. Only these fixed messages reach the log; API responses, exception texts and
tokens do not.
Generator 0 remains reserved; at most 1,023 starts, including burned slots, fit
one retained lab dataset.

The infrastructure chart creates only product/order ServiceAccounts and grants
them `get`/`patch` on the single named allocator ConfigMap. Ledger, workloads and
the default account have no allocation permissions.
A fail-closed admission policy rejects counter rollback, out-of-range values,
individual and collection deletion, and changes to the immutable `shardshop-generator-identity`
ConfigMap. That identity pins the registry's original UID. Neither state object
is rendered by Helm, so reapplying the chart cannot reset it.
The guard matches the admitted object's name, including collection deletes with
an empty request name. `up.sh` probes both allocator objects through raw collection
requests with `dryRun=All`, as well as individual dry-run requests. Namespace deletion
cannot remove those two objects and
leaves the namespace terminating while the guard is active; other ConfigMaps
remain outside the guard.
The guard uses Kubernetes' [validating admission policy](https://kubernetes.io/docs/reference/access-authn-authz/validating-admission-policy/)
and the allocator uses [conditional API updates](https://kubernetes.io/docs/reference/using-api/api-concepts/#updates-to-existing-resources).

On first setup, `cluster-create.sh` initializes the allocator automatically after admission is
effective. Recovery of missing or partial state is an operator procedure for a lab
with retained IDs; never create a replacement registry based on absence alone.

```bash
bash microservices/shardshop/scripts/cluster-create.sh
bash microservices/shardshop/scripts/up.sh
```

Automatic creation uses `create`, never apply/replace/patch, and refuses to proceed
unless both state objects are absent. A lost creation response can leave partial
state; startup does not repair it by guessing. Establish its history before operator
recovery. Normal
stop/start, pod replacement and database restore preserve the ConfigMaps. Keep
the latest allocator identity and high-water backup outside kind, separately
from database dumps. Never apply an older allocator snapshot over live state.
Restoring an entire older etcd/cluster snapshot also restores its admission
history; that is unsupported without independently establishing the latest mark.
If that mark is unavailable, keep ID-producing services stopped. Removing the
guard or resetting state is allowed only after retiring all prior emitters,
databases, broker data, backups and other replay inputs.

Build and verify the affected modules, then run the generator scenarios:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml \
  -pl shardshop-product,shardshop-order -am clean verify
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pintegration clean verify
```

`GeneratorAllocation.feature` of [`shardshop-testing`](../../shardshop-testing/RUNBOOK.md#integration-tests-with-testcontainers)
runs the packaged product against a real Kubernetes API with the RBAC and the
retention policy of the chart. It checks concurrent starts, restarts, the IDs of the
created sellers, the permissions of each service account, the policy, and the
failures. It consumes no lab slots. The
[live deployment check](../../shardshop-testing/RUNBOOK.md#live-deployment-check) shows a new generator ID after a product pod
replacement. The [product and order images and Deployments](../../infra/RUNBOOK.md#deploy-with-upsh)
start `quarkus-run.jar`
directly. They use the owning service's ServiceAccount and set
`SHARDSHOP_ID_REGISTRY_UID` from the identity ConfigMap. The images contain no
kubectl, and no tool binaries are downloaded or stored in the repository.
