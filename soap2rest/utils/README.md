# Soap to Rest - Utils

<sub>[Back to soap2rest](../README.md)</sub>

## Contents
1. [Purpose](#1-purpose)
2. [What It Contains](#2-what-it-contains)
3. [How `@ExecutionTime` Works](#3-how-executiontime-works)
4. [Example Usage](#4-example-usage)
5. [Logging Behavior](#5-logging-behavior)
6. [Scope and Limits](#6-scope-and-limits)
7. [Build and Test](#7-build-and-test)
8. [Related Docs](#8-related-docs)

## 1. Purpose
<sub>[Back to top](#soap-to-rest---utils)</sub>

`utils` holds shared utility code used by the other `soap2rest` modules.

At the moment this module is intentionally small. Its main responsibility is lightweight method timing through the `@ExecutionTime` annotation and aspect.

## 2. What It Contains
<sub>[Back to top](#soap-to-rest---utils)</sub>

- `ExecutionTime`
  - marker annotation used on methods that should be timed
- `ExecutionTimeAspect`
  - Spring AOP interceptor that measures elapsed execution time and writes a log entry

## 3. How `@ExecutionTime` Works
<sub>[Back to top](#soap-to-rest---utils)</sub>

1. A method is annotated with `@ExecutionTime`
2. Spring AOP intercepts the method call
3. the aspect records start and end timestamps
4. the aspect logs the method signature and elapsed time in milliseconds
5. the original method result is returned unchanged

This keeps timing concerns out of controller and service logic.

## 4. Example Usage
<sub>[Back to top](#soap-to-rest---utils)</sub>

```java
@ExecutionTime
public ResponseEntity<String> getDefault() {
    return ResponseEntity.ok("Hello World!");
}
```

## 5. Logging Behavior
<sub>[Back to top](#soap-to-rest---utils)</sub>

- intended for lightweight local observability
- useful in controller and integration paths where quick timing feedback is helpful
- currently logs at `INFO`

## 6. Scope and Limits
<sub>[Back to top](#soap-to-rest---utils)</sub>

- this is not a full metrics or tracing solution
- it does not publish Prometheus, Micrometer, or OpenTelemetry data
- it should stay small and cross-cutting; business logic does not belong here

## 7. Build and Test
<sub>[Back to top](#soap-to-rest---utils)</sub>

From repository root:

```bash
mvn -pl soap2rest/utils -am test
```

## 8. Related Docs
<sub>[Back to top](#soap-to-rest---utils)</sub>

- module overview: [../README.md](../README.md)
- overall architecture: [../ARCHITECTURE.md](../ARCHITECTURE.md)
