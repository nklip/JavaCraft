# netty-client

## Module purpose

`netty-client` contains the Netty-based client implementation for the echo example.
It opens TCP connections, sends line-delimited commands, and reads server responses through a client handler queue.

## How it is structured

- `dev.nklip.javacraft.echo.netty.client.NettyClient`
  - Main client API: `openConnection()`, `sendMessage(...)`, `readMessage()`, `close()`, `run()`.
  - Uses a shared Netty `EventLoopGroup` with reference counting, so load tests do not create one event loop per client.
  - Appends `\r\n` for outbound protocol framing.
- `dev.nklip.javacraft.echo.netty.client.NettyClientInitializer`
  - Builds client pipeline with:
    - `DelimiterBasedFrameDecoder`
    - `StringDecoder`
    - `StringEncoder`
    - `NettyClientHandler`
  - Uses a latch to expose handler safely after pipeline init.
- `dev.nklip.javacraft.echo.netty.client.NettyClientHandler`
  - Stores inbound messages in a bounded queue.
  - Returns next message via timed polling.
  - Logs and preserves interrupt flag on interruption.
- `dev.nklip.javacraft.echo.netty.client.NettyClientApplication`
  - CLI entry point.
  - Default port: `8076`.
  - Port validation range: `0..65535`.

## Protocol behavior

- Send any text -> `Did you say '...'?`
- Send `stats` (case-insensitive) -> `Simultaneously connected clients: N`
- Send empty line -> `Please type something.`
- Send `bye` (case-insensitive) -> `Have a good day!` and server closes the connection.

On connect, client also receives greeting lines from server:

- `Welcome to <host>!`
- `It is <timestamp> now.`

## Build and test

Run tests only for this module:

```bash
mvn -pl echo/netty/netty-client test
```

## Run manually

1. Start `netty-server` first (for example on port `8076`).
2. Run main class:
   - `dev.nklip.javacraft.echo.netty.client.NettyClientApplication`
3. Optional first argument: port number (`0..65535`, default `8076`).

