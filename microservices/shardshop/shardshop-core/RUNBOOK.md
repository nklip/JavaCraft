# Core runbook

[ShardShop](../README.md) › [Core](README.md) › **Runbook** · [Architecture](ARCHITECTURE.md)

This runbook lists the owner-local wire contracts and their checks. The libraries
have their own runbooks: [common](common/RUNBOOK.md), [ID generation](idgen/RUNBOOK.md),
[routing](sharding/RUNBOOK.md) and [workload control](workload-control/RUNBOOK.md).

## HTTP and message contracts

| Owner | Contract |
|---|---|
| Product | [OpenAPI](../shardshop-product/src/main/resources/contracts/openapi.yaml): seller/product POST and GET, including lookup by creation key |
| Order | [OpenAPI](../shardshop-order/src/main/resources/contracts/openapi.yaml): buyer POST/GET, durable order-ID allocation, order PUT/status GET |
| Order | [RecordOrder JSON Schema](../shardshop-order/src/main/resources/contracts/record-order.schema.json) and [command example](../shardshop-order/src/main/resources/contracts/examples/record-order.json) |
| Ledger | [Result JSON Schema](../shardshop-ledger/src/main/resources/contracts/ledger-result.schema.json), [recorded example](../shardshop-ledger/src/main/resources/contracts/examples/ledger-recorded.json) and [rejected example](../shardshop-ledger/src/main/resources/contracts/examples/ledger-rejected.json) |

Each document is self-contained and packaged under its owner's `contracts/`
classpath directory. Applications do not import another module's schemas or models;
clients keep their own DTOs. HTTP examples appear inline. The two result examples
show alternative decisions for one example command, never two outcomes for one
retained order. Example IDs illustrate the format and are not an issued dataset.

Workloads select a pinned local dataset version and send its ID-free creation
payloads with stable `Idempotency-Key` values. Product/order generate and return
IDs; clients preserve those strings on subsequent requests. Catalog lookups by
creation key recover existing IDs without creating rows or sharing a manifest.

IDs are strings in `1..9223372036854775807`. Prices and costs are nonnegative
decimal strings with exactly two fractional digits. Profit balances have the same
format, but they can be negative. Allocation accepts `runName` and a string
`requestOrdinal` in `0..9223372036854775807`, and returns the same persisted order ID
on retries/restarts. Orders accept at most 1,000 distinct seller/product pairs.
The order contract defines normalization, validation precedence and retries;
message schemas define `requestFingerprint`, command/result correlation, immutable
snapshots and timestamp-preserving replay. Cross-field arithmetic, issuance and
stored-state checks require provider tests beyond schema validation.

The product HTTP, order HTTP, legacy v1 command and ledger result examples form
one EUR purchase from an EU seller in France: seller/product IDs use generator 2 on
2026-02-01, the buyer ID uses generator 1 on 2026-03-01, and
order/saga/command/transport IDs use generator 1 at 2026-10-06T11:59:59Z, before
command publication. Seller and buyer regions match the existing routing rule.
The separate [JPY v2 command example](../shardshop-order/src/main/resources/contracts/examples/record-order-jpy.json)
uses distinct order, product and transport identities for a US buyer purchasing
from a Japanese seller routed to ASIA; it is not a rewrite of the EUR order.
These are illustrative wire examples, not workload IDs; actual creation responses
use the services' live generators. Workload definitions contain no IDs.

Message timestamps require uppercase UTC `T`/`Z` and at most six fractional digits.
Producers truncate instants to microseconds **before** both persistence and envelope
creation. Replays retain the saved instant and, for the same message ID, the exact
saved envelope. Company/person names, product descriptions, address text and run
names reject NUL and unpaired UTF-16 surrogates before IO,
while supplementary Unicode characters are valid. Integer fields accept `2`, `2.0`
and `2e0` as the same exact value and reject fractional values such as `2.5`; the
fingerprint serializes the normalized integer.

Providers enforce strict decoding before they construct typed DTOs or touch
storage. Product and order both enforce this. Enable unknown-property rejection and duplicate-member
detection, require JSON string tokens for string fields, and validate integral
numeric values exactly before conversion. Do not rely on Jackson's default string
coercion or float-to-int truncation. Contract/provider tests must include raw JSON
with duplicate keys, numeric IDs, `2.0`, `2e0`, `2.5`, NUL and unpaired surrogates.

### Contract checks

Each module that owns a contract checks it in a Maven unit test, without
infrastructure: `ProductApiContractTest`, `OrderApiContractTest`,
`RecordOrderContractTest` and `LedgerResultContractTest`. They check:

- the document: the bundled OpenAPI 3.1 schema or the JSON Schema 2020-12
  meta-schema, each schema object against the meta-schema, in-document references,
  path parameters and unique operation IDs;
- every inline example, also in referenced responses, the message examples, and an
  example for each response body;
- the boundaries of IDs, status URLs, money, text, timestamps, integers and the
  creation and response objects;
- the rules inside the module: the order fingerprint, the snapshot total, the
  reserved IDs and their times, the ledger decision fields, and the illustrative
  service-issued ID and home-region rules.

`Contracts.feature` of `shardshop-testing` checks what connects the contracts of
different modules, and the creation payloads of the workload datasets
([integration tests](../shardshop-testing/RUNBOOK.md#integration-tests-with-testcontainers)). The tests and
the scenarios use `ContractDocument` and `ExampleIds` from the test JAR of
`shardshop-common`. They validate with the platform-managed Vert.x JSON Schema
library; [VERSIONS.md](../VERSIONS.md#step-312-contract-test-dependencies) records its
qualification and the gaps that the helper corrects.

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml \
  -pl shardshop-product,shardshop-order,shardshop-ledger -am clean verify
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pintegration clean verify
```

Packaged routing startup checks run in the default Maven `verify` lifecycle.
