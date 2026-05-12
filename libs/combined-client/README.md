# libs/combined-client

Multi-transport client that composes QUIC, SCTP, HTX, and Reactor elements
into a single `CombinedClientElement`, then drives an RPC session loop over
aria2c-style argument parsing.

## What it is (mechanically)

- **CombinedClientElement** — an `AsyncContextElement` that owns four child
  elements (QuicElement, SctpElement, HtxElement, ReactorElement).  `open()`
  cascades to all children; `close()` reverses the order.  Provides
  `executeRpc(command, args)` that dispatches to the appropriate child based
  on a string command ("quic", "sctp", "ipfs"/"htx", "reactor").

- **CombinedClientApp** — an `AsyncContextElement` that parses aria2c-style CLI
  args into `Aria2Switches`, opens a `CombinedClientElement`, and runs an RPC
  session loop via `supervisorScope` + `Channel<CharSequence>`.  Commands are
  space-split and forwarded to `combinedClient.executeRpc()`.

- **ReactorElement** — a placeholder `AsyncContextElement` with an empty
  `process(arg)` method.  Stub for future reactor/command dispatch.

## Source layout

```
src/
  commonMain/kotlin/borg/trikeshed/combined/
    CombinedClientElement.kt  — composite element, RPC dispatch
    CombinedClientApp.kt      — CLI arg parsing, RPC session loop
    ReactorElement.kt         — placeholder element with empty process()
  commonTest/kotlin/.../
    CombinedClientElementTest.kt  — lifecycle + executeRpc integration
```

## Key / Element / Reactor status

| Shape                   | Status    | Notes                                          |
|-------------------------|-----------|------------------------------------------------|
| CombinedClientElement   | Active    | Owns quic/sctp/htx/reactor children            |
| CombinedClientElement.Key | Active  | AsyncContextKey<CombinedClientElement> companion|
| CombinedClientApp       | Active    | Session orchestrator, Channel-based RPC loop    |
| ReactorElement          | Stub      | process() is empty, no-op                      |
| ReactorElement.Key      | Stub      | AsyncContextKey<ReactorElement> companion       |
| ReactorSupervisor       | None      | No SupervisorJob wiring for child elements      |

## Dependencies

(from build.gradle.kts)
- `:libs:common` (AsyncContextElement, AsyncContextKey, ElementState)
- `:libs:quic` (QuicElement)
- `:libs:ngsctp` (SctpElement)
- `:libs:htx-client` (HtxElement, HtxClientRequest, Aria2Switches)
- kotlinx-coroutines (supervisorScope, Channel, launch)

## RPC dispatch table

| Command    | Target      | Behaviour                                    |
|------------|-------------|----------------------------------------------|
| quic       | quic        | Echoes args (stub)                           |
| sctp       | sctp        | Echoes args (stub)                           |
| ipfs, htx  | htx         | Builds HtxClientRequest with Aria2Switches, |
|            |             | calls htx.requestHandler(), returns status   |
| reactor    | reactor     | Calls reactor.process(firstArg) (no-op)      |
| (other)    | —           | Returns "Unknown command: ..."               |
