# Identifier generation architecture

[ShardShop](../../ARCHITECTURE.md) › [Core](../ARCHITECTURE.md) › **Identifier generation** · [Runbook](RUNBOOK.md)

`shardshop-idgen` gives each product and order JVM one bounded Snowflake
generator. Before that generator exists, the JVM reserves a fresh generator ID
that no other process start has used.

- **Owns:** `IdGenerator`, `CheckedTimeSource` and `IdGenerationUnavailableException`
  in `dev.nklip.javacraft.shardshop.idgen`, their unit tests and the Snowflake
  dependency. Its `IdConfiguration` mapping, `IdGenerationConfiguration` CDI
  producer and injection tests live in `dev.nklip.javacraft.shardshop.idgen.config`;
  the `idgen.allocation` package reserves generator IDs.
- **Used by:** product and order only. Workloads and ledger have no generator and
  no allocator access.
- **Boundaries:** idgen has no common or sharding dependency; sharding has no idgen
  or Snowflake dependency. Routing and ID domain classes have no framework imports.
  Each library owns its relevant test properties and has `META-INF/beans.xml` for
  Quarkus discovery. Product and order depend directly on both libraries and retain
  their own packaged startup tests.

## Identifier generation and representation

### Snowflake layout

| Bits | Field | Rule |
|---:|---|---|
| 1 | Sign | Always zero |
| 41 | Milliseconds since the epoch **2026-01-01T00:00:00Z** | Valid while `0 <= ticks < 2^41`; generation expires at **2095-09-07T15:47:35.552Z** |
| 10 | Generator ID | Live IDs 1–1023; generator 0 is reserved for historical fixtures |
| 12 | Sequence | Overflow throws an exception instead of a wait |

Treat epoch, layout, and generation contract as immutable for the lifetime of
retained data.

### Generation

Generate new application IDs only inside product and order, with the pinned dependency
`de.mkammerer.snowflake-id:snowflake-id:0.0.2`. Use it for `sellerId`, `productId`, `buyerId`, `orderId`,
`sagaId`, logical `commandId`, and transport `messageId` wherever a new ID is
required. Reuse existing correlation/business IDs on retries. Order reserves
both a command's transport ID and its result's transport ID when committing a
new command envelope; ledger copies the reserved result ID. Item positions can
remain order-local integers rather than requiring another global ID.

Use `shardshop-idgen`'s `IdGenerator` through its shared process-scoped CDI
producer in each owning service. It wraps `SnowflakeIdGenerator` configured explicitly with
`createCustom`, a checked wrapper around `MonotonicTimeSource`, `Structure(41, 10, 12)`, and
`Options.SequenceOverflowStrategy.THROW_EXCEPTION`. The overflow option avoids the
default unbounded spin-wait. The library's lock serializes concurrent callers;
never create a generator per request or thread. See the
[upstream implementation](https://github.com/phxql/snowflake-id/tree/v0.0.2).

The wrapper implements the library's `TimeSource` and validates the exact tick it
returns on every call: `0 <= ticks < 2^41`. Check before the library masks the
timestamp bits; the library alone cannot detect wrap on a fresh process. The
wrapper retains millisecond tick duration and the configured epoch. An
out-of-range source must throw immediately, never clamp or wrap.

### Identifier format and validation

Java models use `long`/`Long`, and PostgreSQL identifier/correlation columns use
`BIGINT` with positive-value constraints and their existing uniqueness/foreign-key
rules. HTTP paths use canonical decimal text. All ID fields in JSON requests,
responses, broker envelopes, fixture files, and OpenAPI/JSON Schema contracts are
**strings**, for example `"1006632960004096"`, never JSON numbers. This preserves
values above JavaScript's exact-integer range.

- Require a whole-string match of ASCII `[1-9][0-9]{0,18}` using Java
  `Matcher.matches()` or Python `re.fullmatch`, followed by a checked range
  validation from 1 through 9223372036854775807. Anchors alone do not replace
  whole-string matching; never use `find()` or `re.match` for this check.
- Reject zero, signs, padding, whitespace (including trailing LF/CRLF and leading
  TAB), fractions, exponents, overflow, and numeric JSON tokens before conversion
  or hashing; do not coerce them. HTTP validation returns `400 INVALID_REQUEST`,
  not an uncaught conversion error. Hash the accepted text without modification.
- The shared `IdParser` in `shardshop-common` enforces this contract once for all
  consumers. Malformed string content detected while reading the current JSON token
  uses the same fixed validation error; plain stream `IOException` propagates.
  Providers still validate the surrounding document and map its syntax errors.

IDs are identifiers, not secrets or authorization tokens, and generation
time/worker bits are not a public business-time ordering contract.

### Who creates which IDs

Product owns seller/product ID generation; order owns buyer/order and
saga/command/message ID generation. The order producer obtains each order ID from
order before its first PUT and retains it with the exact creation payload through
bounded retries/status polling.

Ledger has no generator: each `RecordOrder` includes an order-generated
`resultMessageId`, which ledger uses as its result's `messageId`. A duplicate
command republishes the same result identity; a new reconciliation command carries
a newly reserved result ID. Do not allocate a separate ID for a ledger entry
already uniquely identified by its order ID.

## Generator identity across processes and restarts

Generator **0** remains reserved for historical illustrative fixtures and is
never used by live processes or workloads. Workload datasets contain no IDs;
all newly created entities use service live generators. For this finite local
lab, a named `shardshop-snowflake-generators` ConfigMap keeps a monotonically
increasing allocation counter for live IDs **1-1023**. Each product or order JVM
reserves one fresh ID with a Kubernetes resource-version compare-and-set at **every
start**, also after a container restart in the same pod. Java code in that JVM does
this before the generator exists. No kubectl binary, launcher process, init
container, sidecar, separate pod or service takes part.

### Allocation at startup

- At startup, the eager CDI producer of the generator reads the registry and sends
  a JSON patch through the Kubernetes API with Apache HttpClient 5. The request
  uses the projected service account token. The client trusts only the service
  account CA, and the server certificate must name the host of the URL.
- The patch tests the UID, the resource version and the counter, and it replaces
  the counter in the same request. The live generator receives its allocation only
  after success.
- The producer starts after the other startup beans. A bean that injects the
  generator creates it at once, thus product and order create their stores and
  checked service settings at an earlier startup priority than their services. An
  invalid routing snapshot, connection, pool or service setting stops the start
  before a reservation and uses no slot.
- Each request is bounded by five seconds within a 30-second allocation deadline;
  CAS retries back off from 25 to 500 milliseconds. Concurrent starts retry CAS
  conflicts with that bounded deadline.
- A refused PATCH (401, 403 or 404) stops the start at once. A conflict, a policy
  denial, a server error or a lost response burns the candidate. An uncertain
  reservation burns that slot and obtains another; it never guesses or reuses one.
- A fixed `shardshop.id.generator-id` is only for disposable tests outside
  Kubernetes: a JVM in a pod refuses it, and a fixed ID together with a registry
  UID stops the start.

### Registry protection

- The ConfigMap stores `data.highWaterMark`; a separate immutable
  `shardshop-generator-identity` ConfigMap pins its original UID.
- Give narrow RBAC for this one precreated ConfigMap only to product and order.
  Among ShardShop application ServiceAccounts, only product/order receive
  `get`/`patch` on the registry. Cluster infrastructure accounts, including CNPG
  and Kubernetes controllers, can have broader ClusterRole permissions; the
  admission guard also applies to their ordinary API requests.
- The infrastructure's fail-closed admission policy matches the admitted object's
  name and rejects individual or collection deletion, non-increasing counters and
  identity changes. Namespace deletion consequently leaves the namespace
  terminating with the two allocator objects retained.
- Registry creation is performed by `cluster-create.sh` only for a cluster created
  during that invocation, after the guard is effective; normal startup of an
  existing cluster only checks it.

The [startup runbook](RUNBOOK.md#generator-allocation-at-jvm-startup)
defines the configuration, initialization and verification.

### Limits

Never reuse a slot while any data from that lab can be restored/replayed. This
allows at most 1,023 ID-producing process starts, including burned reservations,
per retained lab dataset. Each `up.sh` rollout uses at least five slots (see the
[deployment lifecycle](../../infra/ARCHITECTURE.md#deployment-pipeline)).
Exhaustion, allocation failure, or missing/stale registry state fails startup
visibly. Preserve the allocation high-water independently of older database
backups, and never roll it back on restore. A destructive fresh-lab reset can
reset the registry only after all old emitters and associated data, broker
deliveries, backups, and replay inputs have been retired. Scaling beyond this
bound needs a separately designed persistent timestamp-fencing/reuse scheme.

### Failure behavior

The monotonic time source protects an active generator from wall-clock steps but
does not persist a high-water mark across JVM restarts; distinct incarnation IDs
provide the restart safety here. Invalid epoch/time range, clock-source failure,
or sequence overflow must fail without emitting an ID. Return a bounded
`503 ID_GENERATION_UNAVAILABLE` if a service needs a new ID and cannot allocate
one; rollback its transaction and retain any consumed broker delivery for the
existing retry/DLQ path. Workload generation backs off with a bounded budget.
Once an ID has been assigned to a logical request, transient failures never
replace it with another ID.
