# soap2rest-rest-api

<sub>[Back to soap2rest](../README.md)</sub>

## Contents
1. [Purpose](#1-purpose)
2. [What It Contains](#2-what-it-contains)
3. [Build and Test](#3-build-and-test)
4. [Related Docs](#4-related-docs)

## 1. Purpose
<sub>[Back to top](#soap2rest-rest-api)</sub>

`rest-api` holds the shared DTOs exchanged between `rest-app` and `soap`. It has no runtime dependencies on either module, keeping the contract lightweight and decoupled.

## 2. What It Contains
<sub>[Back to top](#soap2rest-rest-api)</sub>

- `Metric` — single metric payload (gas or electric reading)
- `Metrics` — wrapper for a list of metrics
- `AsyncJobResultResponse` — async job result payload returned by the polling endpoint

## 3. Build and Test
<sub>[Back to top](#soap2rest-rest-api)</sub>

From repository root:

```bash
mvn -pl soap2rest/rest-api -am test
```

## 4. Related Docs
<sub>[Back to top](#soap2rest-rest-api)</sub>

- module overview: [../README.md](../README.md)
- overall architecture: [../ARCHITECTURE.md](../ARCHITECTURE.md)
- REST backend: [../rest-app/README.md](../rest-app/README.md)
