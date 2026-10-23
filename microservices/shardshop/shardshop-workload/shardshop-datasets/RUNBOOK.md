# Dataset runbook

[ShardShop](../../README.md) › [Workloads](../README.md) › **Dataset runbook** · [Architecture](ARCHITECTURE.md) · [Source provenance](SOURCES.md)

## Workload-owned datasets and service-issued IDs

All definitions and source data live in
[`shardshop-workload/shardshop-datasets`](.).
`CatalogDataset("catalog-v2")` and `BuyerDataset("buyers-v1")` expose immutable,
ID-free definitions. Regional TSV resources are under
`src/main/resources/datasets/catalog-v2/{US,EU,ASIA}.tsv` and
`src/main/resources/datasets/buyers-v1/{US,EU,ASIA}.tsv`. Catalog v2 versions the country-aware payloads and keys; buyers-v1 is unchanged.
Retained catalog-v1 source resources remain available as migration provenance.
Unsupported versions fail locally before any HTTP request.

| Definition | Per region | Total | Payload |
|---|---|---|---|
| Company sellers | US/EU 50; ASIA 51 | 151 | Published company names plus one explicit synthetic VN fixture; immutable home region and country |
| Products | US 50; EU 100; ASIA 51 | 201 | One synthetic product per allowed seller currency; exact policy-compliant price/cost, initial stock 1000 |
| Person buyers | 1000 | 3000 | First name, surname, email, phone, postal address and immutable home region |

The regional coverage is US, the EU27 single market, and an ASIA dataset limited
to China, Vietnam, South Korea and Japan. Company selection uses published brand
value as a popularity proxy, with company deduplication; it is not a ranking of
sales or all companies. Name/surname inputs, country coverage, company selection
and source limitations are recorded in
[`SOURCES.md`](SOURCES.md). Buyer profiles and
contact details are synthetic. Emails use normalized ASCII
`firstName.surname` at the reserved domains `example.com`, `example.net` or
`example.org`. Every region reuses numbers from the fictional NANPA range
`+12025550100`–`+12025550199`; these numbers do not represent local phone formats.
Phone numbers and addresses are fixed in the resources so retries reproduce
the same payload. ASIA name pools use MIT-licensed Faker lists for the four
countries; these plausible names are not a population-frequency ranking.

Use `CatalogDataset.sellersByRegion()` and `BuyerDataset.buyersByRegion()` to
select regional definitions. `CatalogDataset.productsBySeller()` groups products
by the local seller key. Retry keys include the version, region, entity type and
regional ordinal, such as `catalog-v2.US.seller.1`, `catalog-v2.US.product.1` and
`buyers-v1.US.buyer.1`. These groups do not generate or select entity IDs.

A product definition references its local `sellerKey`, which is resolved to the
seller ID returned by product before sending its creation request. These keys
are **retry coordinates, not IDs**: workloads never turn them into Snowflakes or
hash them to construct an entity ID. After a dataset has been released or used,
changing a definition's payload requires a new key/version; a restart reuses its
original key and exact payload.

The clients send `POST /api/v1/sellers`,
`POST /api/v1/sellers/{returnedSellerId}/products`, and `POST /api/v1/buyers`.
`Idempotency-Key` carries the definition's stable key. The service generates and
persists the entity ID before returning `201`; a retry returns `200` with the
same ID. A conflicting payload under that scope/key returns `409`. Seller/buyer
keys are scoped by requested region; product keys are scoped by parent seller ID.
Product persists these keys in the catalog. Order
persists buyer creation keys in ordering, including recovery after restart.

Seller requests contain `companyName`, `region` and immutable `countryCode`. Seller responses also include
service-owned `profitsEarned` balances, starting at `"0.00"` for the seller's
policy currencies. Historical balances remain unchanged. Profit means confirmed
sales margin: `(unitPrice - unitCost) × quantity`, using stored order-item
snapshots. Losses use negative amounts; currencies are never added together.
Retries return current profits and never reset them. Workloads neither send nor
calculate seller balances. Product requests include `description` and `unitCost`;
products remain grouped by their returned seller ID. Buyer requests include
`firstName`, `surname`, `email`, `phone`, and `address` with `line1`, `city`,
`postalCode` and `countryCode`.

The seeder retains returned seller IDs while creating products. Reader and order
producer resolve catalog IDs through ordinary read-only resource lookups:
`GET /api/v1/sellers/by-key/{creationKey}?region=US` and
`GET /api/v1/sellers/{sellerId}/products/by-key/{creationKey}`. The services look
up persisted entities and do not hold or generate workload datasets. Reader GETs
use the recovered seller/product IDs. The workloads start their load only after
these lookups succeed, thus no load runs against missing resources. Buyer creation retries similarly recover the same buyer ID.
Live order IDs continue to come from order's durable allocation API.

`SeederDatasets`, `ReaderDatasets` and `ProducerDatasets` use the local definitions,
send creation/lookup requests, validate returned payloads and canonical string IDs,
and preserve those IDs unchanged. Common's transport bounds response bytes and
the complete response deadline, rejects invalid UTF-8/duplicate JSON properties,
and cancels stalled or interrupted requests. Workload tests use recorded responses
with arbitrary large IDs, including values above 2^53, rather than deriving IDs.
Rejected responses throw `HttpResponseException` with `statusCode()` and an
optional validated `errorCode()`. Malformed error bodies preserve the status;
exception messages contain no response payload. Callers can distinguish a missing
resource, a rejected request and a retryable service failure; retry policy stays
with the workload.

After a change to common, run the
[six-application audit](../../RUNBOOK.md#dependency-and-test-validation).

Maven Enforcer rejects direct, optional and transitive dataset dependencies
outside workloads. `DatasetSourcesTest` checks the retained name pools, the
name/email correspondence of the buyers, the regional coverage and the retained
source hashes. `Contracts.feature` of `shardshop-testing` checks that all 151 seller,
201 product and 3000 buyer creation payloads match the product and order contracts
([contract checks](../../shardshop-core/RUNBOOK.md#http-and-message-contracts)):

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-workload/shardshop-datasets -am clean verify
```

Source notices and licenses ship in the dataset JAR under
`META-INF/shardshop-datasets`. Name pools use US Census public-domain data,
CC BY 4.0 Onomaverse data for EU, and MIT-licensed Faker lists for ASIA; see the
[source terms and ranking limitations](SOURCES.md).

## Offline buyer dataset generator

The development-only Java `BuyerDatasetGenerator` reproduces all three frozen
`buyers-v1` TSVs byte for byte from the retained source pools, without network
calls or entity IDs. It lives in test sources and is excluded from production
JARs. Run this explicit Maven command with `JAVA_HOME` pointing to JDK 25 or newer:

```bash
mvn -B -ntp -f microservices/shardshop/shardshop-workload/shardshop-datasets/pom.xml \
  test-compile exec:exec@regenerate-buyers
```

Outputs are fixed under the dataset module's `target/generated-datasets/buyers-v1/`.
Ordinary builds and lifecycle commands never overwrite source fixtures. The tests
compare generated bytes with all three checked-in files; add `-o` to Maven after
its dependencies are cached for an entirely offline run. See the dataset
[`SOURCES.md`](SOURCES.md) for provenance and
comparison commands. The former Python generator has been removed.
