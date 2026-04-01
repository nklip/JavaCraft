# netty-server

## Module purpose

`netty-server` provides the Netty-based echo server implementation.
It accepts multiple TCP clients, processes line-delimited text requests, and responds using the echo protocol.

## How it is structured

- `dev.nklip.javacraft.echo.netty.server.NettyServer`
  - Server lifecycle API:
    - `start()` for non-blocking startup
    - `run()` for blocking mode (waits on `closeFuture`)
    - `stop()` for graceful resource release
  - Uses separate boss/worker event loop groups.
- `dev.nklip.javacraft.echo.netty.server.NettyServerInitializer`
  - Builds server child pipeline with:
    - `DelimiterBasedFrameDecoder`
    - `StringDecoder`
    - `StringEncoder`
    - `NettyServerHandler`
  - Uses `ChannelGroup` to track connected clients.
- `dev.nklip.javacraft.echo.netty.server.NettyServerHandler`
  - Sends greeting on `channelActive`.
  - Handles protocol commands (`stats`, `bye`, empty, echo).
  - Uses `ChannelFutureListener.CLOSE` for graceful close after `bye`.
  - Supports broadcast helper via `sendToAll(...)`.
- `dev.nklip.javacraft.echo.netty.server.NettyServerApplication`
  - CLI entry point.
  - Default port: `8076`.
  - Port validation range: `0..65535`.

## Server protocol behavior

For each incoming line:

- empty payload -> `Please type something.`
- `stats` (case-insensitive) -> `Simultaneously connected clients: N`
- `bye` (case-insensitive) -> `Have a good day!` then close the channel
- any other text -> `Did you say '...'?`

On connect, server sends:

- `Welcome to <host>!`
- `It is <timestamp> now.`

## Build and test

Run tests only for this module:

```bash
mvn -pl echo/netty/netty-server test
```

## Run manually

Run main class:

- `dev.nklip.javacraft.echo.netty.server.NettyServerApplication`

Optional first argument: port number (`0..65535`, default `8076`).

