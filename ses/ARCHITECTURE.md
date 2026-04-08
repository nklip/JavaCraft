# SES Architecture

<sub>[Back to SES](README.md)</sub>

## Contents
1. [Goal](#1-goal)
2. [Runtime Topology](#2-runtime-topology)
3. [Module Map](#3-module-map)
4. [Event Lifecycle](#4-event-lifecycle)
5. [Simulator Pipeline](#5-simulator-pipeline)
6. [Package Map](#6-package-map)
7. [Testing Strategy](#7-testing-strategy)
8. [Current Tradeoffs](#8-current-tradeoffs)

## 1. Goal
<sub>[Back to top](#ses-architecture)</sub>

`ses` is a small in-memory workflow simulator.

Its main architectural rule is simple:

- workflow threads do not mutate shared workflow state directly
- every state transition is published as an event
- read-side state is derived by listening to those events

That split gives the module two clear responsibilities:

- `ses-events`
  owns the event model and the in-memory publish/subscribe infrastructure
- `ses-simulator`
  owns task generation, validation, execution, reporting, and Spring bootstrapping

## 2. Runtime Topology
<sub>[Back to top](#ses-architecture)</sub>

```mermaid
flowchart LR
    app["SesApplication"]
    launcher["WorkerLauncher"]
    creator["Creator"]
    validator["Validator"]
    worker["Worker"]
    reporter["Reporter"]
    creationQ[("creationQueue")]
    validationQ[("validationQueue")]
    wrapper["EventNotifierWrapper"]
    notifier["EventNotifier"]
    monitor["EventsMonitor"]
    finance["FinanceService"]
    dao["FinanceDao"]

    app --> launcher
    launcher --> creator
    launcher --> validator
    launcher --> worker
    launcher --> reporter

    creator --> creationQ
    validator --> creationQ
    validator --> validationQ
    worker --> validationQ

    creator --> wrapper
    validator --> wrapper
    worker --> wrapper
    wrapper --> notifier
    notifier --> monitor

    validator --> finance
    finance --> dao
    reporter --> monitor
```

Runtime notes:

- the whole simulator runs in a single JVM
- both queues are in-memory `PriorityBlockingQueue<Task>` instances
- `FinanceDao` is in-memory too; there is no external database
- `EventsMonitor` is a projection of the latest event per `taskId`, not the workflow source of truth

### Startup sequence

```mermaid
sequenceDiagram
    participant App as SesApplication
    participant Config as SesSimulatorConfiguration
    participant Context as AnnotationConfigApplicationContext
    participant Launcher as WorkerLauncher
    participant Creator
    participant Validator
    participant Worker
    participant Reporter

    App->>Context: create Spring context with Config
    Context-->>App: initialized bean graph
    App->>Context: registerShutdownHook()
    App->>Context: getBean(WorkerLauncher)
    Context-->>App: launcher
    App->>Launcher: launch()
    Launcher->>Creator: start thread
    Launcher->>Validator: start thread
    Launcher->>Worker: start thread
    Launcher->>Reporter: start thread
```

## 3. Module Map
<sub>[Back to top](#ses-architecture)</sub>

| Module | ArtifactId | Responsibility | Depends on |
|---|---|---|---|
| `ses/events` | `ses-events` | event contracts, event types, listener registry, event dispatch, latest-state monitor | Spring context, SLF4J API |
| `ses/simulator` | `ses-simulator` | task pipeline, finance validation, Spring bootstrapping, command-line entrypoint | `ses-events`, Spring context, SLF4J Simple |

```mermaid
graph TD
    events["ses-events"]
    simulator["ses-simulator"]

    simulator --> events
```

## 4. Event Lifecycle
<sub>[Back to top](#ses-architecture)</sub>

Every task moves through the simulator by producing typed events.

The stable identity is `taskId`, not title:

- titles are display labels only
- different tasks may share the same title
- `EventsMonitor` stores the latest event by `taskId`

### Accepted path

```mermaid
sequenceDiagram
    participant Creator
    participant Wrapper as EventNotifierWrapper
    participant Notifier as EventNotifier
    participant Monitor as EventsMonitor
    participant CreationQ as creationQueue
    participant Validator
    participant Finance as FinanceService
    participant Dao as FinanceDao
    participant ValidationQ as validationQueue
    participant Worker

    Creator->>Wrapper: createdEvent(task)
    Wrapper->>Notifier: notify(CreatedEvent)
    Notifier->>Monitor: store latest event for taskId
    Creator->>CreationQ: add(task)

    Validator->>CreationQ: poll()
    Validator->>Finance: isEnoughMoney(code, estimate)
    Finance->>Dao: find finance code
    Validator->>Finance: updateFinance(code, estimate)
    Finance->>Dao: store reduced capacity
    Validator->>Wrapper: acceptedEvent(task)
    Wrapper->>Notifier: notify(AcceptedEvent)
    Notifier->>Monitor: replace latest event for taskId
    Validator->>ValidationQ: add(task)

    Worker->>ValidationQ: poll()
    Worker->>Wrapper: runningEvent(task)
    Wrapper->>Notifier: notify(RunningEvent)
    Notifier->>Monitor: replace latest event for taskId
    Worker->>Wrapper: completedEvent(task)
    Wrapper->>Notifier: notify(CompletedEvent)
    Notifier->>Monitor: replace latest event for taskId
```

### Rejected path

```mermaid
sequenceDiagram
    participant Creator
    participant CreationQ as creationQueue
    participant Validator
    participant Finance as FinanceService
    participant Wrapper as EventNotifierWrapper
    participant Notifier as EventNotifier
    participant Monitor as EventsMonitor

    Creator->>CreationQ: add(task)
    Validator->>CreationQ: poll()
    Validator->>Finance: isEnoughMoney(code, estimate)
    Validator->>Wrapper: rejectedEvent(task)
    Wrapper->>Notifier: notify(RejectedEvent)
    Notifier->>Monitor: replace latest event for taskId
    Note over Validator: task is dropped from the pipeline
```

## 5. Simulator Pipeline
<sub>[Back to top](#ses-architecture)</sub>

The simulator stages are intentionally narrow:

1. `Creator`
   creates random tasks and publishes `CreatedEvent`
2. `Validator`
   checks finance capacity, publishes `AcceptedEvent` or `RejectedEvent`, and forwards only accepted tasks
3. `Worker`
   publishes `RunningEvent`, simulates work with sleeps, then publishes `CompletedEvent`
4. `Reporter`
   periodically asks `EventsMonitor` for the current task snapshot and prints it

`WorkerLauncher` is the orchestration boundary:

- creates the four long-lived worker loops
- connects them to the two queues
- shares the finance and event services
- owns executor startup and shutdown

## 6. Package Map
<sub>[Back to top](#ses-architecture)</sub>

### `ses-events`

| Package | Responsibility |
|---|---|
| `dev.nklip.javacraft.ses.events` | event contracts, statuses, priorities, notifier, monitor, subscription manager |
| `dev.nklip.javacraft.ses.events.impl` | concrete event types and the Spring adapter implementation |

Key classes:

| Class | Purpose |
|---|---|
| `Event` | common contract for workflow events |
| `EventNotifier` | fan-out point that dispatches one event to subscribed listeners |
| `EventsSubscriptionsManager` | thread-safe listener registry keyed by event class |
| `EventsMonitor` | latest-state projection keyed by `taskId` |
| `BaseEvent` | shared event state, equality, and ordering rules |

### `ses-simulator`

| Package | Responsibility |
|---|---|
| `dev.nklip.javacraft.ses.simulator` | entrypoint and top-level launcher |
| `dev.nklip.javacraft.ses.simulator.config` | Spring component scan bootstrap |
| `dev.nklip.javacraft.ses.simulator.flow` | creator, validator, worker, reporter loop stages |
| `dev.nklip.javacraft.ses.simulator.service` | queue access, finance rules, task-to-event translation |
| `dev.nklip.javacraft.ses.simulator.db` | in-memory finance repository |
| `dev.nklip.javacraft.ses.simulator.model` | `Task` and `FinanceCode` model objects |

Key classes:

| Class | Purpose |
|---|---|
| `SesApplication` | command-line entrypoint |
| `SesSimulatorConfiguration` | Java-based Spring bootstrap |
| `WorkerLauncher` | starts and stops the four pipeline threads |
| `EventNotifierWrapper` | converts `Task` into concrete event objects |
| `FinanceService` | business rules around finance capacity |
| `QueueService` | owns the two shared in-memory queues |

## 7. Testing Strategy
<sub>[Back to top](#ses-architecture)</sub>

Testing is split along the same module boundary:

- `ses-events` tests cover event ordering, event dispatch, latest-state projection, enum/value behavior, and the Spring subscription adapter
- `ses-simulator` tests cover:
  - queue and finance model behavior
  - task/event translation
  - launcher wiring
  - Spring bootstrap
  - flow-loop behavior for `Creator`, `Reporter`, `Validator`, and `Worker`

The loop tests intentionally use Mockito spies to stub `busyWait(...)`:

- that keeps them deterministic
- avoids real sleeps
- still exercises the real `run()` method logic

## 8. Current Tradeoffs
<sub>[Back to top](#ses-architecture)</sub>

- the system is intentionally in-memory only; restarting the JVM loses all task and finance state
- threads communicate through in-memory queues rather than a durable message broker
- workflow timing is random, which is good for simulation but not for deterministic runtime behavior
- the reporter writes directly to stdout instead of using a structured reporting or metrics layer
- the monitor stores only the latest event per task, not the full event history
