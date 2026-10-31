# shardshop-idgen

[ShardShop](../../README.md) › [Core](../README.md) › **Identifier generation**

`shardshop-idgen` gives each product and order JVM one bounded Snowflake generator. Before the generator
exists, the JVM reserves a fresh generator ID that no other process start used.

- **Owns:** `IdGenerator`, `CheckedTimeSource` and `IdGenerationUnavailableException` in
  `dev.nklip.javacraft.shardshop.idgen`, their unit tests and the Snowflake dependency. The `IdConfiguration`
  mapping, the `IdGenerationConfiguration` CDI producer and the injection tests are in
  `dev.nklip.javacraft.shardshop.idgen.config`. The `idgen.allocation` package reserves generator IDs.
- **Used by:** product and order only. Workloads and ledger have no generator and no allocator access.
- **Boundaries:** idgen does not depend on common or sharding. Sharding does not depend on idgen or Snowflake.
  The routing and ID domain classes have no framework imports. Each library owns its test properties and has
  `META-INF/beans.xml` for Quarkus discovery. Product and order depend directly on both libraries and keep their
  own packaged startup tests.

## Design

### Identifier generation and representation

#### Snowflake layout

| Bits | Field | Rule |
|---:|---|---|
| 1 | Sign | Always zero |
| 41 | Milliseconds since the epoch `2026-01-01T00:00:00Z` | Valid while `0 <= ticks < 2^41`. Generation expires after `2095-09-07T15:47:35.551Z` |
| 10 | Generator ID | Live IDs 1-1023. Generator 0 is reserved for illustrative fixtures |
| 12 | Sequence | Overflow throws an exception and does not wait |

The epoch, the layout and the generation contract are immutable for the lifetime of retained data.

#### Generation

Generate new application IDs only in product and order, with the pinned dependency
`de.mkammerer.snowflake-id:snowflake-id:0.0.2` (see the
[upstream implementation](https://github.com/phxql/snowflake-id/tree/v0.0.2)). Use it for `sellerId`,
`productId`, `buyerId`, `orderId`, `sagaId`, the logical `commandId` and the transport `messageId`. Reuse
existing correlation and business IDs on retries. When order commits a new command envelope, it reserves the
transport ID of the command and the transport ID of its result. Ledger copies the reserved result ID. Item
positions can stay order-local integers.

Each owning service uses `IdGenerator` through the shared process-scoped CDI producer. It wraps
`SnowflakeIdGenerator`, configured with `createCustom`, a checked wrapper around `MonotonicTimeSource`,
`Structure(41, 10, 12)` and `Options.SequenceOverflowStrategy.THROW_EXCEPTION`. This option avoids the default
unbounded spin-wait. The library lock serializes concurrent callers, so never create a generator for each
request or thread.

The wrapper implements the `TimeSource` of the library and validates the exact tick on every call:
`0 <= ticks < 2^41`. It checks before the library masks the timestamp bits, because the library alone cannot
detect a wrap in a fresh process. The wrapper keeps the millisecond tick duration and the epoch. An
out-of-range source throws at once and never clamps or wraps.

`nextId()` returns a positive `long`, or throws `IdGenerationUnavailableException` with code
`ID_GENERATION_UNAVAILABLE`. Sequence exhaustion (4,096 IDs in one millisecond), backwards time, clock-source
failure and timestamp overflow never return an ID and never start a spin or retry loop.

#### Identifier format and validation

Java models use `long` or `Long`. PostgreSQL identifier and correlation columns use `BIGINT` with
positive-value constraints and their uniqueness and foreign-key rules. HTTP paths use canonical decimal text.
All ID fields in JSON requests, responses, broker envelopes, fixture files and OpenAPI and JSON Schema
contracts are **strings**, for example `"1006632960004096"`, never JSON numbers. Thus values above the
exact-integer range of JavaScript stay exact.

- Require a whole-string match of ASCII `[1-9][0-9]{0,18}` with Java `Matcher.matches()` or Python
  `re.fullmatch`, then a checked range validation from 1 through 9223372036854775807. Never use `find()` or
  `re.match` for this check.
- Reject zero, signs, padding, whitespace (also trailing LF or CRLF and leading TAB), fractions, exponents,
  overflow and numeric JSON tokens before conversion or hashing. Do not coerce them. HTTP validation returns
  `400 INVALID_REQUEST`. Hash the accepted text without change.
- The shared `IdParser` in `shardshop-common` enforces this contract once for all consumers. Malformed string
  content that is detected while it reads the current JSON token gives the same fixed validation error. A plain
  stream `IOException` propagates. Providers validate the surrounding document and map its syntax errors.

IDs are identifiers, not secrets or authorization tokens. The time and generator bits are not a public
business-time ordering contract.

#### Who creates which IDs

Product creates seller and product IDs. Order creates buyer, order, saga, command and message IDs. The order
producer obtains each order ID from order before its first PUT, and keeps it with the exact creation payload
through the bounded retries and status polling.

Ledger has no generator. Each `RecordOrder` has an order-generated `resultMessageId`, which ledger uses as the
`messageId` of its result. A duplicate command republishes the same result identity. A new reconciliation
command carries a newly reserved result ID. Do not allocate a separate ID for a ledger entry, because its order
ID identifies it.

### Generator identity across processes and restarts

Generator 0 is never used by live processes or workloads. Workload datasets contain no IDs, and all new
entities use the live generators of the services.

For this finite local lab, the ConfigMap `shardshop-snowflake-generators` keeps an increasing allocation
counter for live IDs 1-1023. Each product or order JVM reserves one fresh ID with a Kubernetes
resource-version compare-and-set (CAS) at every start, also after a container restart in the same pod. Java
code in that JVM does this before the generator exists. No kubectl binary, launcher process, init container,
sidecar, separate pod or service takes part.

#### Allocation at startup

- The eager CDI producer of the generator reads the registry. Then it sends a JSON patch through the Kubernetes
  API with Apache HttpClient 5. The request uses the projected service account token. The client trusts only the
  service account CA, and the server certificate must name the host of the URL.
- The patch tests the UID, the resource version and the counter, and replaces the counter in the same request.
  The generator receives its allocation only after success.
- The producer starts after the other startup beans, and a bean that injects the generator creates it at once.
  Thus product and order create their stores and checked service settings at an earlier startup priority than
  their services. An invalid routing snapshot, connection, pool or service setting stops the start before a
  reservation and uses no slot.
- Each request lasts at most five seconds within a 30-second allocation deadline. CAS retries back off from 25
  to 500 milliseconds, and concurrent starts retry CAS conflicts within that deadline.
- A refused PATCH (401, 403 or 404) stops the start at once. A conflict, a policy denial, a server error or a
  lost response burns the candidate. An uncertain reservation burns its slot, and the next attempt advances
  beyond both the observed counter and the uncertain candidate. The producer never guesses or reuses a slot.
- A fixed `shardshop.id.generator-id` is only for disposable tests outside Kubernetes. A JVM in a pod refuses
  it, and a fixed ID together with a registry UID stops the start.

#### Registry protection

- The ConfigMap stores `data.highWaterMark`. A separate immutable ConfigMap, `shardshop-generator-identity`,
  pins its original UID.
- Only the ServiceAccounts of product and order receive `get` and `patch` on this one precreated ConfigMap.
  Cluster infrastructure accounts, including CNPG and Kubernetes controllers, can have broader ClusterRole
  permissions. The admission guard also applies to their ordinary API requests.
- A fail-closed admission policy matches the name of the admitted object. It rejects individual and collection
  deletion (also with an empty request name), non-increasing counters, out-of-range values and identity
  changes. Thus namespace deletion leaves the namespace terminating with the two allocator objects retained.
  Other ConfigMaps are outside the guard. The guard uses the Kubernetes
  [validating admission policy](https://kubernetes.io/docs/reference/access-authn-authz/validating-admission-policy/),
  and the allocator uses [conditional API updates](https://kubernetes.io/docs/reference/using-api/api-concepts/#updates-to-existing-resources).
- `cluster-create.sh` creates the registry, only for a cluster that it creates in that invocation, after the
  guard is effective. Normal startup of an existing cluster only checks the registry.

#### Limits

Never reuse a slot while any data from the lab can be restored or replayed. Thus a retained lab dataset allows
at most 1,023 ID-producing process starts, including burned reservations. Each `up.sh` rollout uses at least
five slots (see the [deployment lifecycle](../../infra/ARCHITECTURE.md#deployment-pipeline)). Exhaustion,
allocation failure and missing or stale registry state fail the startup visibly.

Preserve the allocation high-water mark independently of older database backups, and never roll it back on a
restore. A destructive fresh-lab reset can reset the registry only after all old emitters and associated data,
broker deliveries, backups and replay inputs are retired. A larger bound needs a separately designed persistent
timestamp-fencing or reuse scheme.

#### Failure behavior

The monotonic time source protects an active generator from wall-clock steps. It does not persist a high-water
mark across JVM restarts, and the distinct incarnation IDs give the restart safety. An invalid epoch or time
range, a clock-source failure or a sequence overflow must fail without an emitted ID.

If a service needs a new ID and cannot allocate one, it returns a bounded `503 ID_GENERATION_UNAVAILABLE`. It
rolls back its transaction and keeps a consumed broker delivery for the existing retry and DLQ path. Workload
generation backs off with a bounded budget. After an ID is assigned to a logical request, transient failures
never replace it with another ID.

## Operation

### Bounded Snowflake generation

Product and order each inject one eager, process-scoped `IdGenerator` through the shared CDI producer. Common,
sharding, ledger and workloads contain neither generator classes nor Snowflake. The ownership and the failure
codes are in [Design](#identifier-generation-and-representation). HTTP providers map
`IdGenerationUnavailableException` to `503 ID_GENERATION_UNAVAILABLE` and abort their transaction.

A production start requires a generator ID in `1..1023`, and there is no production default. No runtime
creation path or workload emits generator-0 IDs. A deployed JVM reserves its ID through the registry
([generator allocation](#generator-allocation-at-jvm-startup)). The fixed `shardshop.id.generator-id`, also
configurable through `SHARDSHOP_ID_GENERATOR_ID`, is only for disposable tests without retained data. It does
not reserve or persist an ID.

Controlled-clock tests exercise concurrency, sequence exhaustion and recovery, source failures, backwards time,
epoch and layout compatibility, exact timestamp limits and the maximum positive `long`. CDI tests check the
shared instance identity. Packaged startup tests cover system-property and environment-variable configuration,
including missing, empty, malformed and out-of-range live IDs.

```bash
# idgen unit and CDI suite
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-core/idgen -am clean verify
# routing unit and CDI suite
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-core/sharding -am clean verify
# both owners and their shared libraries
mvn -B -ntp -f microservices/shardshop/pom.xml \
  -pl shardshop-product,shardshop-order -am clean verify
```

The [six-application audit](../../RUNBOOK.md#dependency-and-test-validation) produces the dependency and SBOM
reports for the ownership checks.

### Generator allocation at JVM startup

Product and order reserve a fresh generator ID at every JVM start. The eager CDI producer does this before the
generator exists:

1. It reads `shardshop-snowflake-generators` through the Kubernetes API.
2. It sends one JSON patch that tests the UID, the resource version and `data.highWaterMark` of the registry,
   and replaces the counter.
3. Only an acknowledged reservation becomes the generator ID. The JVM then logs
   `Reserved generator ID <n> for this JVM start.` at INFO.

The [allocation design](#allocation-at-startup) has the request, timeout and retry rules. The packaged startup
tests set a registry UID without a CA file, and show that every local configuration error stops the start
before a reservation.

| Property | Environment variable | Default and rule |
|---|---|---|
| `shardshop.id.registry.uid` | `SHARDSHOP_ID_REGISTRY_UID` | None. A value selects the reservation. The product Deployment takes it from the `registryUid` key of the immutable `shardshop-generator-identity` ConfigMap |
| `shardshop.id.registry.url` | `SHARDSHOP_ID_REGISTRY_URL` | `https://kubernetes.default.svc`. Only `https`, with a host and an optional port, and no path, query or user information |
| `shardshop.id.registry.namespace` | `SHARDSHOP_ID_REGISTRY_NAMESPACE` | `shardshop` |
| `shardshop.id.registry.token-file` | `SHARDSHOP_ID_REGISTRY_TOKEN_FILE` | `/var/run/secrets/kubernetes.io/serviceaccount/token`, read again for each request |
| `shardshop.id.registry.ca-file` | `SHARDSHOP_ID_REGISTRY_CA_FILE` | `/var/run/secrets/kubernetes.io/serviceaccount/ca.crt` |
| `shardshop.id.generator-id` | `SHARDSHOP_ID_GENERATOR_ID` | None. A fixed ID for disposable tests outside Kubernetes. A packaged JVM in a pod (`KUBERNETES_SERVICE_HOST` is set) refuses it |

Missing, malformed, inaccessible, replaced or exhausted state fails the startup. The diagnostics identify
exhaustion, stale identity, allocation deadlines, the API client configuration, and read and update failures of
the registry, with their next checks. Only these fixed messages reach the log. API responses, exception texts
and tokens do not.

The infrastructure chart creates only the ServiceAccounts of product and order, and grants them `get` and
`patch` on the single named allocator ConfigMap. Ledger, workloads and the default account have no allocation
permissions. Helm does not render the two state objects, so a reapplied chart cannot reset them. `up.sh` probes
both allocator objects through raw collection requests with `dryRun=All`, and through individual dry-run
requests.

On first setup, `cluster-create.sh` initializes the allocator after admission is effective:

```bash
bash microservices/shardshop/scripts/cluster-create.sh
bash microservices/shardshop/scripts/up.sh
```

The automatic creation uses `create`, never apply, replace or patch. It refuses to proceed unless both state
objects are absent. A lost creation response can leave partial state, and startup does not repair it by
guessing. Establish the history before the operator recovery. Recovery of missing or partial state is an
operator procedure for a lab with retained IDs. Never create a replacement registry based on absence alone.

CAUTION: Keep the latest allocator identity and high-water backup outside kind, separately from database dumps.
Never apply an older allocator snapshot over live state. A restore of an older etcd or cluster snapshot also
restores its admission history, and this is not supported without an independent proof of the latest mark. If
the mark is not available, keep the ID-producing services stopped. Remove the guard or reset the state only
after all prior emitters, databases, broker data, backups and other replay inputs are retired.

Normal stop and start, pod replacement and database restore keep the ConfigMaps.

Build and verify the affected modules, then run the generator scenarios:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml \
  -pl shardshop-product,shardshop-order -am clean verify
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pintegration clean verify
```

`GeneratorAllocation.feature` of [`shardshop-testing`](../../shardshop-testing/RUNBOOK.md#integration-tests-with-testcontainers)
runs the packaged product against a real Kubernetes API with the RBAC and the retention policy of the chart. It
checks concurrent starts, restarts, the IDs of the created sellers, the permissions of each service account, the
policy and the failures. It uses no lab slots. The
[live deployment check](../../shardshop-testing/RUNBOOK.md#live-deployment-check) shows a new generator ID after
a product pod replacement. The [product and order images and Deployments](../../infra/RUNBOOK.md#deploy-with-upsh)
start `quarkus-run.jar` directly, use the ServiceAccount of the owning service and set
`SHARDSHOP_ID_REGISTRY_UID` from the identity ConfigMap. The images contain no kubectl, and the repository
stores no downloaded tool binaries.
