# EWRS Schema

<sub>[Back to EWRS](README.md)</sub>

This document explains the SQL schema used by EWRS.

It complements:

- [README.md](README.md)
  module/runtime overview and quick start
- [ARCHITECTURE.md](ARCHITECTURE.md)
  runtime flow, write side, read side, and module boundaries

The authoritative DDL still lives in Liquibase:

- [db.changelog-001-initial.yaml](ewrs-app/src/main/resources/db/changelog/db.changelog-001-initial.yaml)

## Contents
1. [Schema roles](#1-schema-roles)
2. [Application tables](#2-application-tables)
3. [Sequences](#3-sequences)
4. [Liquibase tables](#4-liquibase-tables)
5. [How the schema is used](#5-how-the-schema-is-used)

## 1. Schema roles
<sub>[Back to top](#ewrs-schema)</sub>

EWRS uses a small schema with clearly separated responsibilities:

- source of truth:
  `event_store`
- reference data:
  `budget_reference`
- read-side projections:
  `work_request_projection`, `budget_projection`
- projection bookkeeping:
  `projection_checkpoint`

Important rule:

- `event_store` is authoritative
- projection tables are rebuildable
- `ewrs-scenarios` does not add its own tables; it drives `ewrs-app` over HTTP and uses the same schema
- `ewrs-dashboard` does not add its own tables either; it reads the shared EWRS schema in a read-only way for
  visualization

## 2. Application tables
<sub>[Back to top](#ewrs-schema)</sub>

### `event_store`

Defined in [db.changelog-001-initial.yaml](ewrs-app/src/main/resources/db/changelog/db.changelog-001-initial.yaml).

Purpose:

- stores every immutable workflow event in append-only form
- acts as the source of truth for replay, timeline queries, and projection rebuilds

Who writes it:

- `ewrs-app` write side through `EventStoreRepository`

Who reads it:

- command-side aggregate rehydration
- timeline queries
- projection catch-up and rebuild flow
- `ewrs-dashboard` timeline drill-down and event-volume chart

Key columns:

- `id`
  monotonic storage id used by the projector checkpoint and replay ordering
- `event_id`
  stable event identity
- `task_id`
  aggregate/work-request identifier
- `stream_version`
  optimistic stream ordering within one work request
- `event_type`
  concrete workflow event type such as `CreatedEvent` or `RejectedEvent`
- `status`
  business status derived from the event
- `payload`
  event business data in `jsonb`
- `metadata`
  event-sourcing metadata in `jsonb`
- `occurred_at`
  event timestamp

Important constraints and indexes:

- unique `event_id`
- unique `(task_id, stream_version)`
- index on `task_id`
- index on `occurred_at`

### `budget_reference`

Defined in [db.changelog-001-initial.yaml](ewrs-app/src/main/resources/db/changelog/db.changelog-001-initial.yaml).

Purpose:

- stores valid budget codes and their initial available amounts
- acts as deterministic seeded reference data

Who writes it:

- Liquibase seed data

Who reads it:

- budget validation and budget policy logic
- `budget_projection` initialization
- foreign-key target for projection tables
- `ewrs-dashboard` indirectly through the seeded `budget_projection` rows and shared schema constraints

Key columns:

- `budget_code`
  primary key such as `OPS-2026`
- `initial_budget`
  configured total budget for that code

Important constraints:

- primary key on `budget_code`

### `work_request_projection`

Defined in [db.changelog-001-initial.yaml](ewrs-app/src/main/resources/db/changelog/db.changelog-001-initial.yaml).

Purpose:

- stores the current read-model state of each work request
- supports fast query endpoints without replaying all events on every request

Who writes it:

- projector and rebuild flow inside `ewrs-app`

Who reads it:

- `GET /api/v1/work-requests/{requestId}`
- `GET /api/v1/work-requests`
- `ewrs-dashboard` status distribution, recent-request list, and summary cards

Key columns:

- `task_id`
  work-request identifier and primary key
- `title`
- `priority`
- `budget_code`
- `estimate`
- `status`
- `requested_by`
- `last_actor`
- `reason`
  nullable reason for rejection or operator action
- `last_event_id`
- `last_occurred_at`
- `stream_version`

Important constraints and indexes:

- primary key on `task_id`
- foreign key `budget_code -> budget_reference.budget_code`
- index on `status`
- index on `budget_code`

### `budget_projection`

Defined in [db.changelog-001-initial.yaml](ewrs-app/src/main/resources/db/changelog/db.changelog-001-initial.yaml).

Purpose:

- stores the current projected reserved and remaining amount per budget code
- gives the query side a fast budget view

Who writes it:

- projector and rebuild flow inside `ewrs-app`
- initial rows are seeded from `budget_reference`

Who reads it:

- `GET /api/v1/projections/budgets`
- budget-facing assertions in tests and demos
- `ewrs-dashboard` reserved-vs-remaining budget chart

Key columns:

- `budget_code`
  primary key
- `initial_budget`
- `reserved_amount`
- `remaining_budget`
- `last_updated_at`

Important constraints:

- primary key on `budget_code`
- foreign key `budget_code -> budget_reference.budget_code`

### `projection_checkpoint`

Defined in [db.changelog-001-initial.yaml](ewrs-app/src/main/resources/db/changelog/db.changelog-001-initial.yaml).

Purpose:

- stores how far the projector has advanced through `event_store`
- lets catch-up restart safely after app restart or notification loss

Who writes it:

- projection coordinator during normal catch-up
- rebuild flow after replay

Who reads it:

- projection startup and notification wake-up flow
- `ewrs-dashboard` projection lag and checkpoint health summary

Key columns:

- `projection_name`
  logical projector id, currently `ewrs-projector`
- `last_event_store_id`
  last applied row id from `event_store`

Important constraints:

- primary key on `projection_name`

## 3. Sequences
<sub>[Back to top](#ewrs-schema)</sub>

### `work_request_id_seq`

Defined in [db.changelog-001-initial.yaml](ewrs-app/src/main/resources/db/changelog/db.changelog-001-initial.yaml).

Purpose:

- allocates new work-request ids

Used by:

- `ewrs-app` write side before appending the initial `CreatedEvent`

Defaults:

- starts at `1000`
- increments by `1`

## 4. Liquibase tables
<sub>[Back to top](#ewrs-schema)</sub>

These are framework-managed tables, not EWRS domain tables:

- `databasechangelog`
  Liquibase migration history
- `databasechangeloglock`
  Liquibase migration lock coordination

They matter operationally, but they are not part of the EWRS business model.

## 5. How the schema is used
<sub>[Back to top](#ewrs-schema)</sub>

The flow is:

1. write-side commands append immutable rows to `event_store`
2. the projector reads unread `event_store` rows after `projection_checkpoint.last_event_store_id`
3. current-state projections are written into `work_request_projection` and `budget_projection`
4. query endpoints read projections, while timeline endpoints read `event_store` directly

That means:

- if a projection table is lost, it can be rebuilt
- if `event_store` is lost, the system loses its source of truth
- `budget_reference` is configuration/reference data, not event history
