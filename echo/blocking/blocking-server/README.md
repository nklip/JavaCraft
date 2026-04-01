# blocking-server

## Module purpose

`blocking-server` is the blocking socket server module for the Blocking Echo example.  
It accepts TCP clients, processes text-line messages, and responds with a simple echo protocol.

Two server variants are provided:

- `PlatformServer` - per-client handler runs on platform threads.
- `VirtualServer` - per-client handler runs on virtual threads.

Both are built on the same common server core.

## How it is structured

- `common.dev.nklip.javacraft.echo.blocking.server.MultithreadedServer`
  - Accept loop and shared server lifecycle.
  - Guard against duplicate `run()` calls via `running` flag.
  - Graceful shutdown via `close()` (implements `AutoCloseable`):
    - flips running flag
    - closes current `ServerSocket`
    - unblocks `accept()`
  - Wraps `startUpClient(...)` in base `RuntimeException` handling and closes failed client sockets.
- `common.dev.nklip.javacraft.echo.blocking.server.ServerThread`
  - Per-client protocol processing.
  - UTF-8 input/output.
  - Per-client read timeout (`setSoTimeout(2000)`).
  - Per-server-instance connection counting through shared `AtomicInteger`.
- `platform.dev.nklip.javacraft.echo.blocking.server.PlatformServer`
  - Starts each `ServerThread` on a platform daemon thread.
- `virtual.dev.nklip.javacraft.echo.blocking.server.VirtualServer`
  - Starts each `ServerThread` on a virtual thread.
- `common.dev.nklip.javacraft.echo.blocking.server.PortValidator`
  - Validates CLI port (`1..65535`) with default fallback (`8075`).
- `PlatformServerApplication` / `VirtualServerApplication`
  - Entry points for running server manually.

## Server protocol behavior

For each received line:

- `""` -> `Please type something.`
- `stats` (case-insensitive) -> `Simultaneously connected clients: N`
- `bye` (case-insensitive) -> `Have a good day!` and connection closes
- Any other text -> `Did you say '...'?`

All responses end with CRLF on the wire.

## Lifecycle and reliability notes

- Constructor only stores config; runtime state is initialized in `run()`.
- Connection counters are incremented/decremented safely in `ServerThread`.
- Unexpected startup failures in client handler creation are handled centrally in `MultithreadedServer`.
- `close()` is idempotent; calling it when server is not running is a no-op.

## Build and test

Run tests only for this module:

```bash
mvn -pl echo/blocking/blocking-server test
```

## Running server manually

Run from your IDE using one of these main classes:

- `platform.dev.nklip.javacraft.echo.blocking.server.PlatformServerApplication`
- `virtual.dev.nklip.javacraft.echo.blocking.server.VirtualServerApplication`

Optional CLI argument: port number (default `8075`).

