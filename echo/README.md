# Echo: Client/Server Implementations
<sub>[Back to JavaCraft](../README.md#micro-java-samples)</sub>

This folder contains three echo implementations with different networking/concurrency models.

## Modules

- [blocking](blocking/README.md) - Blocking socket I/O, benchmarked across platform-thread and virtual-thread server/client combinations.
- [netty](netty/README.md) - Netty event-loop based non-blocking implementation.
- [selector](selector/README.md) - Pure Java NIO selector-based implementation.

## Final Performance Snapshot (All Echo Submodules)

All benchmark suites use the same load shape:
- 100 clients
- 100 messages per client (10,000 total messages)
- 2 warmups (not measured)
- 3 measured runs

Numbers below come from each submodule's final benchmark summary examples.

| Rank | Scenario | Average time (s) | Throughput (msg/s) | Avg (ms/msg) | Delta vs fastest |
|---|---|---:|---:|---:|---:|
| 1 | `blocking`: `VIRTUAL_SERVER+VIRTUAL_CLIENT` | 0.116 | 86128.36 | 0.0116 | +0.000 s (+0.00%) |
| 2 | `blocking`: `VIRTUAL_SERVER+PLATFORM_CLIENT` | 0.132 | 75544.52 | 0.0132 | +0.016 s (+14.01%) |
| 3 | `netty`: `NETTY_SERVER+NETTY_CLIENT` | 0.206 | 48483.37 | 0.0206 | +0.090 s (+77.59%) |
| 4 | `selector`: `SELECTOR_SERVER+SELECTOR_CLIENT` | 0.352 | 28409.09 | 0.0352 | +0.236 s (+203.45%) |
| 5 | `blocking`: `PLATFORM_SERVER+VIRTUAL_CLIENT` | 0.980 | 10203.37 | 0.0980 | +0.864 s (+744.12%) |
| 6 | `blocking`: `PLATFORM_SERVER+PLATFORM_CLIENT` | 1.790 | 5586.09 | 0.1790 | +1.674 s (+1441.84%) |

## Why Metrics Look Like This

- In the blocking implementation, server thread model dominates total cost; virtual-thread server combinations are much faster because blocked I/O parking is cheaper than running many OS threads.
- Platform-thread server combinations are slower due to higher OS thread scheduling/context-switch overhead and memory pressure at higher connection counts.
- Netty performs strongly because one event-loop architecture avoids one-thread-per-connection blocking overhead, but still pays pipeline/event-loop coordination overhead.
- Selector implementation is also non-blocking and single-loop driven, but manual selector/key/buffer orchestration and single-thread serialized progress can cap throughput earlier than the best virtual-thread blocking path for this specific short-message localhost benchmark.
- The benchmark is intentionally synthetic and localhost-only; absolute numbers are machine/JVM/OS dependent, but the ordering trend is consistent with the concurrency models.

## How To Re-run Final Summaries

```bash
mvn -pl echo/blocking/blocking-testing -am verify -Dcucumber.filter.tags='@PerformanceSummary'
mvn -pl echo/netty/netty-testing -am verify -Dcucumber.filter.tags='@PerformanceSummary'
mvn -pl echo/selector/selector-testing -am verify -Dcucumber.filter.tags='@PerformanceSummary'
```
