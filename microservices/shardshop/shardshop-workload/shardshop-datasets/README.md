# shardshop-datasets

[ShardShop](../../README.md) › [Workloads](../README.md) › **Datasets** · [Source provenance](SOURCES.md)

`shardshop-datasets` is a pure library in `shardshop-workload` that holds all dataset definitions. A definition
fixes a payload and its creation retry key, never an entity ID, because product and order issue all IDs.

- **Used by:** the three workload applications only. Maven Enforcer rejects direct, optional and transitive
  dataset dependencies outside the workloads.
- **Contains no:** entity IDs, generator, clock, shard router or service wire models. It uses the pure common
  currency policy.

## Design

### Datasets

| Dataset | Contents | Resources |
|---|---|---|
| `catalog-v2` | 151 sellers and 201 products (US 50, EU 100, ASIA 51). It has 50 ranked companies per region and the explicitly synthetic Vietnam Sample Company to exercise VND. Each seller has one product per allowed currency | `src/main/resources/datasets/catalog-v2/{US,EU,ASIA}.tsv` |
| `buyers-v1` | 1,000 synthetic profiles per region (3,000 buyers) | `src/main/resources/datasets/buyers-v1/{US,EU,ASIA}.tsv` |
| `catalog-v1` | Unchanged resources that keep migration provenance | — |

`CatalogDataset("catalog-v2")` and `BuyerDataset("buyers-v1")` expose immutable, ID-free definitions from the
regional TSV files. Catalog-v2 replaces the catalog-v1 creation payloads and keys. Unsupported versions fail
locally before any HTTP request.

| Definition | Per region | Total | Payload |
|---|---|---|---|
| Company sellers | US and EU 50, ASIA 51 | 151 | Published company names and one synthetic VN fixture. Immutable home region and country |
| Products | US 50, EU 100, ASIA 51 | 201 | One synthetic product per allowed seller currency. Exact policy-compliant price and cost, initial stock 1000 |
| Person buyers | 1000 | 3000 | First name, surname, email, phone, postal address and immutable home region |

### Synthetic data rules

- EU covers the EU27 single market. ASIA is limited to China, Vietnam, South Korea and Japan.
- Published brand value is the proxy for company popularity, with company deduplication. It is not a ranking of
  sales or of all companies. [`SOURCES.md`](SOURCES.md) records the name and surname inputs, the country
  coverage, the company selection and the source limitations.
- Profiles do not identify verified people. Email local parts normalize first name and surname to ASCII and use
  the reserved domains `example.com`, `example.net` or `example.org`.
- All regions reuse numbers from the fictional NANPA range `+12025550100`-`+12025550199`, so phone formats do
  not indicate the country of the buyer. Phone and postal address values are fixed in the resources, so retries
  reproduce the same payload.
- ASIA name pools contain plausible names from MIT-licensed Faker lists for the four countries. They are not a
  population-frequency ranking.

### Definitions and keys

Each definition contains a stable creation retry key and its immutable payload. `sellersByRegion()` and
`buyersByRegion()` expose the regional groups. `productsBySeller()` groups products by their local seller key
until creation returns a real seller ID. These groups do not generate or select entity IDs.

Versioned keys include the version, region, entity type and ordinal, for example `catalog-v2.US.seller.1`,
`catalog-v2.US.product.1` and `buyers-v1.US.buyer.1`. A product definition references its local `sellerKey`,
which is resolved to the seller ID that product returned before the creation request is sent. The keys are
**retry coordinates, not IDs**. Workloads never turn them into Snowflakes or hash them to construct an entity
ID. After a dataset is released or used, a change of a payload requires a new version, and no retained key can
be reused with a different payload. A restart reuses its original key and exact payload.

### Related contracts

See [catalog resolution](../README.md#catalog-resolution) for client validation and startup, and the
provider-owned [product](../../shardshop-product/ARCHITECTURE.md) and
[order](../../shardshop-order/ARCHITECTURE.md) contracts for service-issued IDs and durable retries. The
[routing library](../../shardshop-core/sharding/README.md#version-2-routing-and-topology) owns the routing
algorithm and the immutable inventory.

## Operation

### Workload-owned datasets and service-issued IDs

The clients send `POST /api/v1/sellers`, `POST /api/v1/sellers/{returnedSellerId}/products` and
`POST /api/v1/buyers`. `Idempotency-Key` carries the stable key of the definition. The service generates and
persists the entity ID before it returns `201`. A retry returns `200` with the same ID. A conflicting payload
under that scope and key returns `409`. Seller and buyer keys are scoped by the requested region, and product
keys by the parent seller ID. Product persists its keys in the catalog. Order persists buyer creation keys in
ordering, including the recovery after a restart.

Seller requests contain `companyName`, `region` and the immutable `countryCode`. Seller responses also include
the service-owned `profitsEarned` balances, which start at `"0.00"` for the policy currencies of the seller.
Profit means confirmed sales margin, `(unitPrice - unitCost) × quantity`, from the stored order-item snapshots.
Losses use negative amounts, and currencies are never added together. Retries return the current profits and
never reset them. Workloads neither send nor calculate seller balances. Product requests include `description`
and `unitCost`. Buyer requests include `firstName`, `surname`, `email`, `phone`, and `address` with `line1`,
`city`, `postalCode` and `countryCode`.

The seeder keeps the returned seller IDs while it creates products. The reader and the order producer resolve
catalog IDs through read-only resource lookups:
`GET /api/v1/sellers/by-key/{creationKey}?region=US` and
`GET /api/v1/sellers/{sellerId}/products/by-key/{creationKey}`. The services look up persisted entities and do
not hold or generate workload datasets. The reader GETs use the recovered seller and product IDs. The workloads
start their load only after these lookups succeed, so no load runs against missing resources. Buyer creation
retries recover the same buyer ID. Live order IDs come from the durable allocation API of order.

`SeederDatasets`, `ReaderDatasets` and `ProducerDatasets` use the local definitions, send creation and lookup
requests, validate the returned payloads and canonical string IDs, and keep those IDs unchanged. The transport
of common bounds the response bytes and the complete response deadline, rejects invalid UTF-8 and duplicate
JSON properties, and cancels stalled or interrupted requests. Workload tests use recorded responses with
arbitrary large IDs, including values above 2^53, and do not derive IDs. A rejected response throws
`HttpResponseException` with `statusCode()` and an optional validated `errorCode()`. A malformed error body keeps
the status, and exception messages contain no response payload. Callers can distinguish a missing resource, a
rejected request and a retryable service failure, and the workload owns the retry policy.

After a change to common, run the [six-application audit](../../RUNBOOK.md#dependency-and-test-validation).
`DatasetSourcesTest` checks the retained name pools, the name and email correspondence of the buyers, the
regional coverage and the retained source hashes. `Contracts.feature` of `shardshop-testing` checks that all 151
seller, 201 product and 3000 buyer creation payloads match the product and order contracts
([contract checks](../../shardshop-core/README.md#http-and-message-contracts)):

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-workload/shardshop-datasets -am clean verify
```

Source notices and licenses ship in the dataset JAR under `META-INF/shardshop-datasets`. Name pools use US
Census public-domain data, CC BY 4.0 Onomaverse data for EU, and MIT-licensed Faker lists for ASIA. See the
[source terms and ranking limitations](SOURCES.md).

### Offline buyer dataset generator

The development-only Java `BuyerDatasetGenerator` reproduces all three frozen `buyers-v1` TSVs byte for byte
from the retained source pools, without network calls or entity IDs. It is in the test sources and is excluded
from production JARs. Run this explicit Maven command with `JAVA_HOME` set to JDK 25 or newer:

```bash
mvn -B -ntp -f microservices/shardshop/shardshop-workload/shardshop-datasets/pom.xml \
  test-compile exec:exec@regenerate-buyers
```

The outputs are in `target/generated-datasets/buyers-v1/` of the dataset module. Ordinary builds and lifecycle
commands never overwrite the source fixtures. The tests compare the generated bytes with all three checked-in
files. Add `-o` to Maven after the dependencies are cached for an entirely offline run. See
[`SOURCES.md`](SOURCES.md) for provenance and comparison commands.
