# Product reader runbook

[ShardShop](../../README.md) › [Workloads](../README.md) › [Product reader](README.md) › **Runbook** · [Architecture](ARCHITECTURE.md)

## Load

After the lookups, the workers read randomly selected products with
`GET /api/v1/sellers/{sellerId}/products/{productId}`. One shared schedule gives
the request starts one total rate. After a stall, the reader does not send the
missed requests later. The [reader control page](../../shardshop-core/workload-control/README.md)
changes this rate, and pauses and resumes the reads, while the reader runs. Each response must keep the issued IDs and the dataset
payload; only the stock can change.

## HTTP client

The reader sends HTTP requests with the classic Apache HttpClient 5 client,
which the Quarkus platform manages. The JDK HTTP client has no setting for a
connection limit or a connection lifetime. The defaults agree with the
[HTTP connection policy](ARCHITECTURE.md#http-connection-policy):

| Item | Reader behavior |
|---|---|
| Pool | A strict pool of up to 16 HTTP/1.1 connections. Apache opens a connection only when no free connection exists, and uses free connections in FIFO order. After the first lifetime, the number of open connections follows the load: request rate multiplied by latency |
| Warm-up | 16 workers send the 450 startup lookups at the same time, thus all 16 connections open before the load starts |
| Rotation | Apache's time to live of 30 s: the pool closes an older connection when it would lease it again, thus after its current request. The replacement connection gives the Service a new backend selection |
| Idle connection | Apache's idle-connection evictor checks each second and closes a connection that is not used for 10 s |
| Failed connection | Apache closes a connection after a failure or a cancel |
| Deadlines | 2 s to connect. A scheduled cancel ends the complete read after 5 s, including the wait for a connection and all attempts |
| Repeated attempts | Only after a failure without an HTTP response, for example a refused, reset or closed connection: a maximum of 3 attempts, 100 ms apart. Timeouts are not repeated |
| Response body | A body larger than 2 MiB is an invalid response; the reader reads at most 2 MiB plus one byte |

## Configuration

| Property | Default | Purpose |
|---|---|---|
| `shardshop.reader.product-url` | None. Startup fails without it | Base URL of the product Service |
| `shardshop.reader.catalog-version` | `catalog-v2` | Dataset version. Other versions fail before HTTP |
| `shardshop.reader.request-rate` | `200` | Initial request starts per second for all workers, 0–10000 with at most three decimals; the control page changes the live rate |
| `shardshop.reader.workers` | `16` | Concurrent workers, from the number of connections to 256 |
| `shardshop.reader.connections` | `16` | HTTP/1.1 connections, 1–64 |
| `shardshop.reader.connect-timeout` | `2s` | Connection deadline. It cannot be longer than the request deadline |
| `shardshop.reader.request-deadline` | `5s` | Complete deadline of one read. Startup fails below 5 s, because product returns `503` within its 4-second server deadline |
| `shardshop.reader.max-attempts` | `3` | Attempts after failures without an HTTP response, 1–5 |
| `shardshop.reader.retry-interval` | `100ms` | Fixed delay before a new attempt |
| `shardshop.reader.max-connection-lifetime` | `30s` | Apache time to live of a connection |
| `shardshop.reader.idle-timeout` | `10s` | Time before an unused connection closes |
| `shardshop.reader.report-interval` | `30s` | Time between metric lines, at least 1 s |
| `shardshop.reader.diagnostics.enabled` | `false` | Append bounded scheduling diagnostics to the existing interval reports |

For a diagnostic run, set `SHARDSHOP_READER_DIAGNOSTICS_ENABLED="true"` under
`productReader.configuration`. The existing report and catalog digest keep their
positions; a `scheduling us` suffix adds count, mean, p50/p95/p99 and maximum:

- `wake`: granted pacing slot to observed permit, including condition/lock
  reacquisition; intentional pacing wait is excluded.
- `lateArrival`: arrival under the pacer lock beyond the previous next slot.
  Startup, pause/rate changes and unpaced starts have no valid baseline and are
  excluded. This combines worker availability and stalls, not pure OS latency.
- `dispatch`: observed permit to the timestamp immediately before the HTTP call.

Samples are recorded with completed read results. Each interval also includes
`window={sequence,startNanos,endNanos}` from the reader JVM's monotonic clock;
use their difference for exact duration and sequence/bounds for non-overlap.
These timestamps cannot be compared with the host's monotonic clock. Histograms
use fixed storage (approximately 192 KiB total for interval and cumulative
diagnostics), ceiling-microsecond percentile bounds with approximately 1.6%
bucket width above 127 microseconds, and nanosecond-derived means/maxima.
Diagnostics do not change pacing, rate limits, missed-start or pause semantics.

For a saturation test, increase `request-rate`, and increase `workers` and
`connections` together. One pod sends at most `workers` divided by the latency:
16 workers at 3 ms send approximately 5,300 requests/s. When product saturates,
the reader does not send missed requests later, thus the reported rate is the
accepted rate. Environment variables such as `SHARDSHOP_READER_PRODUCT_URL` also set these
properties. `--check-startup` validates the configuration, including the URL and
the timeouts, and exits without HTTP requests. An unexpected worker failure logs
one error line and stops the reader with exit code 1. The
[workload runbook](../RUNBOOK.md) describes the Deployment, its resource budget and
the [seeding gate](../RUNBOOK.md#seeding-gate).

## Tests

`ProductReader.feature` of the [integration tests](../../shardshop-testing/RUNBOOK.md#integration-tests-with-testcontainers)
runs the packaged reader with its default policy and 5-second reports against the
packaged product, and seeds the catalog with the packaged seeder. It does not
connect to the kind lab. It takes approximately 70 seconds:

1. The reader starts before the seeder. It must wait for the catalog and must not
   start its load. After the seeder run, it must start its load, and its resolution
   digest must equal the seeder digest.
2. Three report intervals must have only `OK` reads at 180–201 requests/s, with all
   three regions, the 16 open connections of the warm-up and no read that waits for
   a connection.
3. While product stops, then starts without replicas, and then starts with
   primaries, the reader must record `TRANSPORT` and `REPLICA_UNAVAILABLE`
   outcomes, continue to run, and recover. No read takes longer than 5.1 seconds.
4. After SIGTERM, the total line must be the last report. The stored rows must not
   change.
5. Against product without read replicas, a reader with a 10-second maximum wait
   must exit with code 1 and one `REPLICA_UNAVAILABLE` lookup error, before the load
   starts.

The connection rotation after the 30-second lifetime and the 5-second read deadline
are client behavior. `ProductRequestsTest` and `ProductReaderTest` check them
against a loopback server. `ReaderConfigurationTest` checks that the packaged
defaults are the time limits of the
[HTTP connection policy](ARCHITECTURE.md#http-connection-policy): a 2-second connect
timeout, a 5-second deadline, a 30-second lifetime and a 10-second idle timeout.

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pintegration clean verify
```
