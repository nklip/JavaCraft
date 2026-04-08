# Simple Event System (ses)

Initially created in 2016.

`ses` is a tiny in-memory workflow simulator split into two modules:
- `ses-events`: the event model and publish/subscribe infrastructure
- `ses-simulator`: a four-thread pipeline that creates, validates, executes, and reports tasks

See [ARCHITECTURE.md](ARCHITECTURE.md) for the full module map, runtime topology, and testing split.

The important design idea is that the simulator never talks to the monitor directly.
Every state change is translated into an event first, and the monitor derives the latest
known state for each task by listening to those events.

## Contents
1. [Architecture guide](#1-architecture-guide)
2. [Events module](#2-events-module)
3. [Simulator module](#3-simulator-module)
4. [Runtime flow](#4-runtime-flow)
5. [Why the pieces are separate](#5-why-the-pieces-are-separate)

## 1. Architecture guide
<sub>[Back to top](#simple-event-system-ses)</sub>

Use [ARCHITECTURE.md](ARCHITECTURE.md) when you want the structural view of the module:

- runtime topology
- module responsibilities
- event lifecycle
- package map
- testing strategy

## 2. Events module
<sub>[Back to top](#simple-event-system-ses)</sub>

We have <b>events</b> package. This package provides core functionality for event-system.

Conceptually we have next entities:
1. Event interface and classes which implements this interface. An event could happen by different reasons so it has different classes.
2. EventListener which listen to an event.
3. EventsMonitor declares how we will handle an event. (for example, we can store them in a database). It keeps the latest event per task id.
4. EventNotifier provides API for sending different type of events.

`EventsMonitor` now uses `taskId` as the stable identity key.
That matters because titles are display labels only; two different tasks can have the same title,
but they must still be tracked as separate workflow items.

## 3. Simulator module
<sub>[Back to top](#simple-event-system-ses)</sub>

`SesApplication` creates `WorkerLauncher` via Java-based Spring configuration (`SesSimulatorConfiguration`) and launches it.

WorkerLauncher component creates 4 threads:
* The first thread creates tasks.
* The second thread validates tasks.
* The third thread does a job for a task.
* The last thread shows status for all tasks. It is basically a simple scheduler.

## 4. Runtime flow
<sub>[Back to top](#simple-event-system-ses)</sub>

At runtime the simulator behaves like a simple pipeline:

1. `Creator` builds a random task and publishes a `CreatedEvent`
2. `Validator` decides whether the selected finance code has enough capacity
3. accepted tasks move to the worker queue and publish `AcceptedEvent`
4. rejected tasks publish `RejectedEvent` and stop there
5. `Worker` publishes `RunningEvent`, simulates work with sleeps, then publishes `CompletedEvent`
6. `Reporter` periodically asks `EventsMonitor` for the latest event per task and prints a snapshot

See [ARCHITECTURE.md](ARCHITECTURE.md) for the startup and event-flow diagrams.

## 5. Why the pieces are separate
<sub>[Back to top](#simple-event-system-ses)</sub>

- `EventNotifierWrapper` keeps simulator code free from direct event-class construction details
- `EventNotifier` is the fan-out point that dispatches events to listeners
- `EventsMonitor` is only a projection of the latest known state, not the source of truth
- `FinanceDao` is intentionally just an in-memory repository, while `FinanceService` owns the budget rules
- `WorkerLauncher` is the orchestration boundary that wires queues, services, and worker threads together
