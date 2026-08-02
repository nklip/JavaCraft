# Non-blocking I/O
<sub>[Back to Echo](../README.md#echo-clientserver-implementations)</sub>

Demonstrates how to write a simple client/server application using just a single thread in Java.

Non-blocking I/O allows you to use a single thread to handle multiple concurrent connections. 

* In NIO based systems, instead of writing data onto output streams and reading data from input streams, we read and write data from “buffers”. You can think of the “buffer” as a temporary storage place and there are different types of Java NIO buffer classes (eg:- ByteBuffer , CharBuffer , ShortBuffer etc..) available for us to use, even though most network programs use ByteBuffer exclusively.
* “Channel” is the medium that transports bulk of data into and out of buffers and it can be viewed as an endpoint for communication. (For example if we take “SocketChannel” class, it reads from and writes to TCP sockets. But the data must be encoded in ByteBuffer objects for reading and writing.)
* Then we need to understand a concept called “Readiness Selection” which basically means “the ability to choose a socket that will not block when data is read or written”.
  
Java NIO has a class called “Selector” that allows a single thread to examine I/O events on multiple channels. That is, this selector can check the readiness of a channel for operations, such as reading and writing. Now remember different channels can be registered with a “Selector” object and you can specify which operations you are interested in observing and a another thing to remember is that each of these channels are assigned a separate “SelectionKey” which serve as a pointer to a channel.

## Modules

- [selector-client](selector-client/README.md): non-blocking client and message sender/listener flow.
- [selector-server](selector-server/README.md): selector-based single-thread server implementation.
- [selector-testing](selector-testing/README.md): Cucumber functional, load, and benchmark scenarios.
- [selector-verification](selector-verification/README.md): aggregate JaCoCo report for client/server unit and E2E coverage.

## Cucumber Performance Benchmark

Performance scenarios are implemented in `selector-testing/src/test/resources/features/selector-benchmark.feature`.

1. SelectorServer + SelectorClient benchmark:
```bash
mvn -pl echo/selector/selector-testing -am verify -Dcucumber.filter.tags='@Performance'
```

2. Final summary (reads persisted benchmark summary from `target/performance-results`):
```bash
mvn -pl echo/selector/selector-testing -am verify -Dcucumber.filter.tags='@PerformanceSummary'
```

The benchmark flow includes warmups (not measured), 3 measured runs, and prints average/median metrics.

### Example Final Result

```text
[PERF][FINAL] average execution time for selector benchmark case:
[PERF][FINAL] 1) SELECTOR_SERVER+SELECTOR_CLIENT average: 0.352 s, throughput=28409.09 msg/s, avg=0.0352 ms/msg, delta=0.000 s (+0.00% vs fastest)
[PERF][FINAL] total measured execution time: 1.056 s (selector test)
[PERF][FINAL] total benchmark scenario time (warmups + measured): 1.742 s (selector test)
```

### Why This Is Expected

- Selector server and selector client use one fixed architecture combination, so benchmark summary contains exactly one ranked result.
- This benchmark still stresses concurrent client activity and message framing logic, so throughput reflects selector-loop efficiency and queueing behavior.
- Warmups reduce cold-start/JIT noise before measured runs.

### Important Notes

- Results are environment-specific (CPU, OS scheduler, JVM version, background load).
- Always compare results from the same machine and same runtime configuration.
