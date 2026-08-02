# Socket Server (Blocking)
<sub>[Back to Echo](../README.md#echo-clientserver-implementations)</sub>

Socket server using <b>blocking I/O</b> that demonstrates a simple client/server application in Java.

Every connection/client uses its own thread model implementation:
- `VirtualThreadClient` (virtual threads)
- `PlatformThreadClient` (platform daemon threads)

By definition, a <u>socket</u> is one endpoint of a two-way communication link between two programs running on different computers on a network. A socket is bound to a port number so that the transport layer can identify the application that data is destined to be sent to.

With blocking I/O, when a client makes a request to connect with the server, the thread that handles that connection is blocked until there is some data to read, or the data is fully written. Until the relevant operation is complete, that thread can do nothing else but wait.

## Modules

- [blocking-client](blocking-client/README.md): platform/virtual clients and client-side behavior tests.
- [blocking-server](blocking-server/README.md): multithreaded blocking socket server.
- [blocking-testing](blocking-testing/README.md): Cucumber scenarios split into:
  - `blocking.feature` (functional, edge-case, high-load)
  - `blocking-benchmark.feature` (performance benchmarks + summary)
- [blocking-verification](blocking-verification/README.md): aggregate JaCoCo report for client/server unit and E2E coverage.

## Cucumber Performance Benchmark

Performance scenarios are implemented in `blocking-testing/src/test/resources/features/blocking-benchmark.feature`
and are designed to run in separate fresh JVM processes:

1. PlatformServer + PlatformThreadClient:
```bash
mvn -pl echo/blocking/blocking-testing -am verify -Dcucumber.filter.tags='@Performance and @ServerPlatform and @ClientPlatform'
```

2. PlatformServer + VirtualThreadClient:
```bash
mvn -pl echo/blocking/blocking-testing -am verify -Dcucumber.filter.tags='@Performance and @ServerPlatform and @ClientVirtual'
```

3. VirtualServer + PlatformThreadClient:
```bash
mvn -pl echo/blocking/blocking-testing -am verify -Dcucumber.filter.tags='@Performance and @ServerVirtual and @ClientPlatform'
```

4. VirtualServer + VirtualThreadClient:
```bash
mvn -pl echo/blocking/blocking-testing -am verify -Dcucumber.filter.tags='@Performance and @ServerVirtual and @ClientVirtual'
```

5. Final comparison (reads persisted benchmark summaries from `target/performance-results`):
```bash
mvn -pl echo/blocking/blocking-testing -am verify -Dcucumber.filter.tags='@PerformanceSummary'
```

The benchmark flow includes warmups (not measured), 3 measured runs, and prints average/median comparison.

### Example Final Result

```text
[PERF][FINAL] 1) VIRTUAL_SERVER+VIRTUAL_CLIENT average: 0.116 s, throughput=86128.36 msg/s, avg=0.0116 ms/msg, delta=0.000 s (+0.00% vs fastest)
[PERF][FINAL] 2) VIRTUAL_SERVER+PLATFORM_CLIENT average: 0.132 s, throughput=75544.52 msg/s, avg=0.0132 ms/msg, delta=0.016 s (+14.01% vs fastest)
[PERF][FINAL] 3) PLATFORM_SERVER+VIRTUAL_CLIENT average: 0.980 s, throughput=10203.37 msg/s, avg=0.0980 ms/msg, delta=0.864 s (+744.12% vs fastest)
[PERF][FINAL] 4) PLATFORM_SERVER+PLATFORM_CLIENT average: 1.790 s, throughput=5586.09 msg/s, avg=0.1790 ms/msg, delta=1.674 s (+1441.84% vs fastest)
```

### Why This Ranking Is Expected

- This benchmark is blocking-I/O heavy with many concurrent clients, which favors virtual threads because parking/unparking is cheaper than OS thread context switching.
- The server-side thread model dominates total time; that is why both `VIRTUAL_SERVER+*` combinations are much faster than both `PLATFORM_SERVER+*` combinations.
- Client-side model still matters, but less than server-side in this setup, so `VIRTUAL_SERVER+PLATFORM_CLIENT` stays close to `VIRTUAL_SERVER+VIRTUAL_CLIENT`.
- Two platform-thread sides (`PLATFORM_SERVER+PLATFORM_CLIENT`) compound scheduling and memory overhead, so it is expected to be the slowest combination.
- In the current benchmark implementation, platform-server runs use a more conservative execution path than virtual-server runs, which further widens the observed gap.

### Important Notes

- Results are environment-specific (CPU, OS scheduler, JVM version, background load).
- Always compare results (average and/or median) from the same machine and same configuration.
- Use warmup iterations to reduce JIT/cold-start noise before measured runs.
