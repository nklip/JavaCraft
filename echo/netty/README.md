# Netty Echo (Non-blocking I/O)
<sub>[Back to Echo](../README.md#echo-clientserver-implementations)</sub>

Demonstrates a client/server echo application built on Netty.

Netty uses an event-driven non-blocking model where channels are handled by event loops instead of one dedicated blocking thread per connection.

## Core concepts

### Channel

A `Channel` represents an open connection that can perform asynchronous read/write operations.

### Future

Netty extends Java future-style control flow with `ChannelFuture`, so operations can be observed or chained without blocking the current thread by default.

### Events and handlers

Netty pipelines process inbound/outbound events through handlers.
Typical inbound events include:

- channel active/inactive
- read events
- exceptions
- user events

See implementation details in `netty-server/src/main/java/my/javacraft/echo/netty/server/NettyServerHandler.java`.

### Encoders and decoders

Protocol payloads are transformed by pipeline codec handlers.
This project uses line-delimited frames with string decoder/encoder handlers on both client and server sides.

## Modules

- [netty-client](netty-client/README.md): Netty client implementation and client-side tests.
- [netty-server](netty-server/README.md): Netty server lifecycle and protocol handling.
- [netty-testing](netty-testing/README.md): Cucumber functional/load/benchmark scenarios.
- [netty-verification](netty-verification/README.md): aggregate JaCoCo report for client/server unit and E2E coverage.

## Cucumber Performance Benchmark

Performance scenarios are implemented in `netty-testing/src/test/resources/features/netty-benchmark.feature`.

1. NettyServer + NettyClient benchmark:
```bash
mvn -pl echo/netty/netty-testing -am verify -Dcucumber.filter.tags='@Performance'
```

2. Final summary (reads persisted benchmark summary from `target/performance-results`):
```bash
mvn -pl echo/netty/netty-testing -am verify -Dcucumber.filter.tags='@PerformanceSummary'
```

The benchmark flow includes warmups (not measured), 3 measured runs, and prints average/median metrics.

### Example Final Result

```text
[PERF][FINAL] average execution time for netty benchmark case:
[PERF][FINAL] 1) NETTY_SERVER+NETTY_CLIENT average: 0.206 s, throughput=48483.37 msg/s, avg=0.0206 ms/msg, delta=0.000 s (+0.00% vs fastest)
[PERF][FINAL] total measured execution time: 0.619 s (single test)
[PERF][FINAL] total benchmark scenario time (warmups + measured): 1.478 s (single test)
```

### Why This Is Expected

- Netty handles many sockets through event-loop scheduling instead of one blocking thread per client.
- Line-based codec and pipeline handlers keep request/response processing lightweight for this protocol.
- Warmups reduce first-run noise (JIT/classloading), so measured runs are more stable.

### Important Notes

- Results are environment-specific (CPU, OS scheduler, JVM version, background load).
- Compare benchmark numbers only on the same machine and runtime configuration.
