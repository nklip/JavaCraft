# selector-client

## Module purpose

`selector-client` contains the non-blocking client side of the Selector Echo example.
It connects to the selector-based single-thread server and exchanges line-delimited UTF-8 messages.

## How it is structured

- `dev.nklip.javacraft.echo.selector.client.SelectorClient`
  - Public client API: `connectToServer()`, `sendMessage(...)`, `readMessage()`, `close()`, `run()`.
  - Starts one listener worker (`SelectorMessageListener`) on a single-thread executor.
  - Interactive loop reads stdin until `bye` or EOF.
- `dev.nklip.javacraft.echo.selector.client.SelectorNetworkManager`
  - Owns `SocketChannel` and `Selector`.
  - Uses non-blocking connect with timeout and `wait/notify` handoff for sender/listener.
  - Stores incoming responses in a bounded queue.
- `dev.nklip.javacraft.echo.selector.client.SelectorMessageSender`
  - Frames outgoing commands with `\r\n`.
  - Uses `SelectionKey`/`Selector` write interest and a pending-write queue.
- `dev.nklip.javacraft.echo.selector.client.SelectorMessageListener`
  - Selector-driven reader for complete `\r\n` framed responses.
  - Preserves meaningful payload whitespace and protects against oversized frames.
- `dev.nklip.javacraft.echo.selector.client.SelectorClientApplication`
  - CLI entry point for manual usage.

## Protocol behavior

- Send any text -> `Did you say '...'?`
- Send `stats` (case-insensitive) -> `Simultaneously connected clients: N`
- Send empty message -> `Please type something.`
- Send `bye` (case-insensitive) -> `Have a good day!` and connection closes.

## Build and test

Run tests for this module:

```bash
mvn -pl echo/selector/selector-client test
```

## Run manually

1. Start `selector-server` first (for example on port `8077`).
2. Run main class:
   - `dev.nklip.javacraft.echo.selector.client.SelectorClientApplication`
3. Optional first argument: port number (`0..65535`, default `8077`).
