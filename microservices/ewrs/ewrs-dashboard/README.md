# EWRS Dashboard

<sub>[Back to EWRS](../README.md)</sub>

`ewrs-dashboard` is the standalone visualization module for ewrs.

It stays read-only and renders:

- current work-request status distribution
- reserved vs remaining budget per budget code
- event-store volume over time
- recent projected requests
- per-request event timelines
- projector checkpoint vs latest stored event lag

The page is rendered with Thymeleaf, and charts are drawn with ECharts in the browser.

## Quick Start

Run these commands from the repository root.

1. Start PostgreSQL:

```bash
docker compose -f microservices/ewrs/compose.yaml up -d
```

2. Start `ewrs-app` once so Liquibase creates the shared EWRS schema:

```bash
mvn -f microservices/ewrs/ewrs-app/pom.xml spring-boot:run
```

3. Start the dashboard in another terminal:

```bash
mvn -f microservices/ewrs/ewrs-dashboard/pom.xml spring-boot:run
```

4. Open:

- Dashboard UI
  `http://localhost:8056/`
- Dashboard Swagger UI
  `http://localhost:8056/swagger-ui.html`
- Dashboard OpenAPI
  `http://localhost:8056/api-docs`

## What It Reads

The dashboard reads the EWRS schema directly and does not issue commands.

Main tables:

- [`event_store`](../SCHEMA.md#event_store)
  timeline drill-down and event volume chart
- [`work_request_projection`](../SCHEMA.md#work_request_projection)
  status distribution and recent request list
- [`budget_projection`](../SCHEMA.md#budget_projection)
  reserved vs remaining budget chart
- [`projection_checkpoint`](../SCHEMA.md#projection_checkpoint)
  projection lag and checkpoint health

## HTTP Surface

Browser page:

- `GET /`
  Thymeleaf dashboard shell

JSON endpoints:

- `GET /api/v1/dashboard/overview`
  one aggregated payload for summary cards, charts, and recent requests
- `GET /api/v1/dashboard/work-requests/{requestId}/timeline`
  ordered event history for a single request

## Notes

- `ewrs-dashboard` is intentionally read-only.
- The dashboard assumes the EWRS schema already exists in PostgreSQL.
- ECharts is loaded from a CDN at page render time to keep the module light.
