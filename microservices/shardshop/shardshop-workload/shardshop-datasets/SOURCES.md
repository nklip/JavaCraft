# Dataset sources and reproducibility

Source selection reviewed on **2026-10-07**; Java reproduction verified on
**2026-10-15**. All source pools, generated fixtures and definitions
belong to this workload module. They contain no entity IDs; product/order create
IDs when workloads submit the payloads.

The source selection provides 50 company sellers and 1,000 synthetic buyers per
region. The current `catalog-v2` adds one explicitly synthetic Vietnam company
to cover VND, for 151 sellers and 201 products. US and EU each retain 1,000 given-name entries and 1,000 surname entries.
ASIA uses the complete deduplicated curated lists from the four Faker person
providers below: 345 given-name entries and 503 surname entries. Reusing name
components yields 250 distinct synthetic buyer emails per Asian country without
padding the source lists. Pool sizes are separate from generated buyer counts.

The names are plausible fixture inputs, not a uniform current ranking of the
1,000 most-used names. US inputs are national Census rankings, EU inputs are an
aggregation of a third-party dataset, and ASIA inputs are unranked test-data
lists. Name sources are US government public data, CC BY 4.0 Onomaverse data,
and MIT-licensed Faker providers. The source-specific notices below travel in
the dataset JAR with the generated fixtures.

## Files and recorded evidence

[`source-manifest.json`](source-manifest.json) records download URLs and SHA-256
checksums for source artifacts and derived files. Source selection and synthetic
profile generation are separate steps: the committed normalized pools are the
inputs to the offline generator.

| Files | Contents |
|---|---|
| [`sources/names/US-given.tsv`](sources/names/US-given.tsv), [`US-surname.tsv`](sources/names/US-surname.tsv) | 1,000 US given names and 1,000 US surnames |
| [`sources/names/EU-given.tsv`](sources/names/EU-given.tsv), [`EU-surname.tsv`](sources/names/EU-surname.tsv) | 1,000 aggregated EU27 given names and 1,000 surnames |
| [`sources/names/ASIA-given.tsv`](sources/names/ASIA-given.tsv), [`ASIA-surname.tsv`](sources/names/ASIA-surname.tsv) | 345 curated given names and 503 surnames from China, Vietnam, South Korea and Japan |
| [`sources/address-patterns.tsv`](sources/address-patterns.tsv) | Fictional example streets, city names and postal-code patterns |
| [`BuyerDatasetGenerator.java`](src/test/java/dev/nklip/javacraft/shardshop/workload/dataset/BuyerDatasetGenerator.java) | Development-only offline generation of the three `buyers-v1` TSVs |
| [`sources/licenses`](sources/licenses) | Faker MIT license and Onomaverse CC BY 4.0 attribution notice |
| [`catalog-selection.json`](catalog-selection.json) | Company source ranks, domicile classifications, aliases, exclusions, selection order and resource checksums |

Name-pool columns are `name`, `emailPart`, `countryCode`, `sourceRank`,
`sourceCount` and `source`. Blank ranks/counts identify unranked lists; they are
not zero-frequency observations. Country codes describe source selection, not
an assertion about a person's nationality. Counts from different sources and
observation periods are not comparable.

## US: Census 2020

The two national top-1,000 tables are from the US Census Bureau:

- [2020 first names, sex and frequency workbook](https://www2.census.gov/topics/genealogy/2020surnames/Names2020_FirstNames_Sex_Top1000.xlsx).
- [2020 last names, race/Hispanic origin and frequency workbook](https://www2.census.gov/topics/genealogy/2020surnames/Names2020_LastNames_RaceHispanic_Top1000.xlsx).

The pools retain the published national order and counts, with names formatted
for display and normalized email parts committed alongside them. No racial,
ethnic or sex attributes are assigned to generated buyers. These are 2020
national rankings, not 2026 birth-name rankings or lists of identifiable people.

## EU27: Onomaverse v2026.06

Names data from [Onomaverse](https://onomaverse.com/datasets), licensed
[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). Source files are the
full `given-name-frequency.csv` and `surname-frequency.csv` assets in the
[v2026.06 release](https://github.com/onomaverse/datasets/releases/tag/v2026.06).
Attribution: The Onomaverse Team, *Onomaverse Names Datasets*, v2026.06.

Selection filters country codes to the 27 EU members, aggregates counts by exact
name across countries and, for given names, genders, then sorts by descending
count with lexical name tie-breaking. The first 1,000 entries form each pool.
The recorded `countryCode` is the largest contributing country for that name;
`sourceCount` is the combined EU27 source count. This transformation and the
added ASCII email parts are changes made for this workload.

The EU country set is AT, BE, BG, HR, CY, CZ, DK, EE, FI, FR, DE, GR, HU, IE, IT,
LV, LT, LU, MT, NL, PL, PT, RO, SK, SI, ES and SE. Coverage and source counts are
those of Onomaverse, not a harmonized census of the entire EU population. The
result is an EU27 pool for the single-market workload, not an authoritative
EU-wide popularity league table. Buyer names and addresses are independently
combined across the region; a generated profile is not a country-specific
cultural or demographic sample.

## ASIA: four-country Faker pools

Only China, Vietnam, South Korea and Japan are included. All four person
providers are pinned to [Faker v40.41.0](https://github.com/joke2k/faker/releases/tag/v40.41.0),
commit `72e2fa4b005231cbe258fd4fc777ea22aa769e39`. Download URLs in the manifest
use that immutable commit, and all downloaded bytes were verified against it.

| Country | Provider | Given names | Surnames |
|---|---|---:|---:|
| China | [`zh_CN`](https://github.com/joke2k/faker/blob/72e2fa4b005231cbe258fd4fc777ea22aa769e39/faker/providers/person/zh_CN/__init__.py) | 131 | 399 |
| Vietnam | [`vi_VN`](https://github.com/joke2k/faker/blob/72e2fa4b005231cbe258fd4fc777ea22aa769e39/faker/providers/person/vi_VN/__init__.py) | 42 | 10 |
| South Korea | [`ko_KR`](https://github.com/joke2k/faker/blob/72e2fa4b005231cbe258fd4fc777ea22aa769e39/faker/providers/person/ko_KR/__init__.py) | 121 | 44 |
| Japan | [`ja_JP`](https://github.com/joke2k/faker/blob/72e2fa4b005231cbe258fd4fc777ea22aa769e39/faker/providers/person/ja_JP/__init__.py) | 51 | 50 |
| Total | | 345 | 503 |

Extraction reads source literals without executing downloaded provider code.
Given names combine male then female lists, followed by the Vietnamese unisex
list. Repeated display names within each country/list are removed, keeping the
first occurrence. Surnames retain the provider's source order. Japanese names
use the display name and associated romanization from the provider's name
pairs. Other email parts use the normalization described below. Provider weights
are omitted: `sourceRank` and `sourceCount` remain blank, and each `source` value
identifies `faker-40.41.0-` followed by the locale. These are curated plausible
names, not evidence of population frequencies or a country popularity ranking.

Faker is distributed under its [MIT license](https://github.com/joke2k/faker/blob/72e2fa4b005231cbe258fd4fc777ea22aa769e39/LICENSE.txt).
The complete notice is retained in [`Faker-MIT.txt`](sources/licenses/Faker-MIT.txt).
The Asian source pools and generated profiles no longer use ChineseNames,
the OSF workbook or NameChart. No Faker package or transliteration library is
needed by the build or runtime.

## Synthetic buyer generation

Run from the repository root:

```bash
mvn -B -ntp -f microservices/shardshop/shardshop-workload/shardshop-datasets/pom.xml test-compile exec:exec@regenerate-buyers
```

Export `JAVA_HOME` to a JDK **25 or newer**, following the ShardShop build
baseline. The named Maven execution launches that JDK with the test classpath.
The generator uses only the JDK and checked-in pools, makes no network calls,
and produces exactly 1,000 profiles in each of
`target/generated-datasets/buyers-v1/US.tsv`, `EU.tsv` and `ASIA.tsv` within this
module. Maven may need its normal build dependencies cached before using `-o`
for an entirely offline invocation. The generator itself needs no downloads.

Generation is an explicit development action: the execution has no lifecycle
phase, and ordinary builds and `up.sh` never regenerate checked-in fixtures.
The tool is compiled from `src/test/java`, excluded from the production JAR,
and adds no runtime dependencies. Output paths are fixed under `target` and
symbolic-link outputs are rejected. Compare generated files with the retained
resources before deliberately copying any reviewed dataset changes into source
control; the command never writes `src/main/resources`.

`BuyerDatasetGeneratorTest` regenerates all three files in temporary module
directories and compares every byte with the frozen resources. It also verifies
repeatability under a changed default locale, invalid or exhausted name pools,
malformed inputs, complete address coverage and output boundaries. UTF-8, LF
line endings, region/country/source ordering, unsigned SHA-256 arithmetic and
the global unique-email search preserve the original `buyers-v1` bytes.

`DatasetSourcesTest` verifies the name pools, the name/email pairing, the country
sets and every retained file hash. `Contracts.feature` of `shardshop-testing`
verifies every creation payload against the product and order contracts:

```bash
mvn -B -ntp -f microservices/shardshop/shardshop-workload/shardshop-datasets/pom.xml clean verify
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pintegration clean verify
```

The dataset JAR carries this document, both provenance manifests and source
license notices under `META-INF/shardshop-datasets`.

US uses its national pool. EU cycles address countries across all 27 members
while choosing names from the pooled EU inputs. ASIA generates **250 profiles
per country**, selecting that country's names/surnames with deterministic
cycling and pairing. This output balance is independent of source-pool sizes;
not every input entry must appear in 1,000 generated profiles.

Display names retain Unicode. The committed `emailPart` values were prepared
with macOS ICU `Any-Latin; Latin-ASCII` transliteration, lowercased and normalized
to ASCII, except Japanese names which use Faker's paired romanizations. These
are fixture spellings, not a promise of an individual's preferred name reading.
Emails have exactly the form `firstName.surname@example.com`,
`firstName.surname@example.net` or `firstName.surname@example.org` using those
parts. [IANA reserves these example domains for documentation](https://www.iana.org/help/example-domains).
Uniqueness comes from deterministic surname/domain selection without numeric
suffixes on names or email parts.

Phones in every region use `+12025550100` through `+12025550199`, selected by
SHA-256 of fixed `buyers-v1` coordinates modulo 100. [NANPA reserves 555-0100
through 555-0199 as fictitious, non-working numbers](https://www.nanpa.com/numbering/555-line-numbers).
Phones are intentionally reused and do not model local dialing conventions.
This small reserved range prevents profiles from using real subscriber ranges.
Addresses combine a numbered fictional example street with the committed
city/postal-code pattern. Buyer profiles are assembled for this workload and
are not records collected from real people.

Generation never creates an entity ID. Regional fixture ordinals become stable
creation retry keys only; the workload still obtains every buyer ID from order.
`buyers-v1` keeps its original payload and keys. `catalog-v2` has new creation
keys because seller country and product currencies change the creation payload.
The retained `catalog-v1` TSVs and selection evidence remain unchanged for
migration and provenance. Change dataset versions and keys before changing a
payload that has already been released or used.

## Company sellers and synthetic products

Company selection uses *Brand Finance Global 500 2026* published brand value as
a popularity proxy. The report URL and downloaded artifact checksum are in the
source manifest. The detailed derivation is
[`catalog-selection.json`](catalog-selection.json).

The selection scans ascending published global rank, filters US/EU27/eligible
ASIA countries, maps obvious platform/product brands to their company names,
deduplicates by the mapped company, and keeps the first 50 companies per region.
It retains the source's country classification and best brand rank. Examples
include Google/YouTube → Alphabet, Facebook/Instagram → Meta Platforms,
Chase/JP Morgan → JPMorgan Chase and WeChat/Tencent → Tencent. Taiwan/Hong Kong
entries TSMC, MediaTek and AIA are excluded from the defined ASIA coverage.
Other published company/group/trading names remain unchanged; this is not
exhaustive ultimate-parent consolidation or a company-sales ranking.

The 50 ASIA sellers comprise 32 from China, 14 from Japan and 4 from South Korea.
Vietnam is eligible but has no selected entry before the cutoff; no country
quota was imposed on company selection. `catalog-v2/US.tsv` and `EU.tsv` copy
the retained selection byte for byte. `catalog-v2/ASIA.tsv` keeps all 50 original
rows and appends `Vietnam Sample Company`, country `VN`, rank `synthetic`.
That row is a fictional coverage fixture, has no published rank or source brand,
and is excluded from the ranked selection. Its source is this workload definition.

`CurrencyPolicy` supplies each seller's product currencies: US → USD;
EU27 → EUR and GBP; CN → CNY; VN → VND; KR → KRW; JP → JPY.
This produces US 50 products, EU 100 products, and ASIA 51 products
(CNY 32, KRW 4, JPY 14, VND 1). Each permitted currency receives one
synthetic product. Prices/costs are `19.95`/`12.50` for two-minor-unit currencies
and `20.00`/`12.00` for JPY, KRW and VND. Wire values always use two decimal
places; zero-minor-unit currencies have no fractional value. Test descriptions,
stock, products and workload profit balances do not represent the named
companies' real catalog, costs, revenue or profits.

The producer's default `rejected-order-percent=-1` is balanced traffic. Each window
of 201 consecutive ordinals starts one order with each product, in an order that
sorts the products by the unsigned SHA-256 of run name, NUL, `first-item`, NUL and
definition key. The order uses that product's currency and up to two following
products of the same currency; VND currently has one product. Thus each product is
in at most three orders of a window, and currencies appear in proportion to their
products. Buyer selection is independent of currency. Explicit values `0..100` scope
a USD/EUR rejection drill and require a ledger allowlist matching that drill. Every
allocation uses a new versioned identity: `<run-name>:catalog-v2:balanced` or
`<run-name>:catalog-v2:usd-eur-<percent>`. The earlier seven-way choice used
`<run-name>:catalog-v2:all`; its allocations stay unchanged.
If this exceeds 200 code points, the identity is `catalog-v2:` followed by the
lowercase SHA-256 of that complete identity. Old allocations keep their old
run names and ordinals; new planning never reuses their identities. A restart
with unchanged run name, dataset and selection mode reconstructs the same
allocation, buyer, currency, products, item order and quantities.
