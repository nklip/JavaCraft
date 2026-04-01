# selector-server

## Module purpose

`selector-server` is a selector-based non-blocking echo server that serves many clients on one server thread.

## How it is structured

- `dev.nklip.javacraft.echo.selector.server.SelectorServer`
  - Main event loop with one `Selector`.
  - Accept/read/write handling by `SelectionKey` readiness (`OP_ACCEPT`, `OP_READ`, `OP_WRITE`).
  - Per-client request buffers and pending-write queues.
  - Bounded request/response frame limits to avoid unbounded memory growth.
  - Graceful stop API: `stop()` sets `running=false` and wakes selector.
- `dev.nklip.javacraft.echo.selector.server.SelectorServerApplication`
  - CLI entry point.
  - Installs shutdown hook to stop server cleanly on JVM shutdown.

## Protocol behavior

Requests are framed by `\r\n` and decoded as UTF-8.

- empty payload -> `Please type something.`
- `stats` (case-insensitive) -> `Simultaneously connected clients: N`
- `bye` (case-insensitive) -> `Have a good day!` then close after queued write is flushed
- any other payload -> `Did you say '...'?`

## Reliability notes

- Oversized request frame closes the client connection.
- Oversized pending response backlog closes slow client connection.
- Connection counter is clamped and never decrements below zero.
- Selector wakeup is used for deterministic shutdown.

## Build and test

Run tests for this module:

```bash
mvn -pl echo/selector/selector-server test
```

## Run manually

Run main class:

- `dev.nklip.javacraft.echo.selector.server.SelectorServerApplication`

Optional first argument: port number (`0..65535`, default `8077`).
