# libs/server

Server-side composition of transport AsyncContextElements (QUIC + SCTP + HTX)
and a request-dispatch adapter for the `htx-general` OpenAPI contract.

## What it is (mechanically)

- **ServerContextFactory** — two top-level functions:
  - `buildServerContext()`: opens QuicElement, SctpElement, and HtxElement,
    folds them into a `CoroutineContext`.
  - `closeServerContext(ctx)`: reverse-order close of all three elements.

- **HtxGeneralServerAdapter** — takes a `CoroutineContext`, extracts
  `HtxElement` via `context[HtxKey]`, and delegates `execute(GeneratedRequest)`
  to `htx.request()`.  Also provides `client()` returning a `HtxGeneralApi`
  that wraps execute() with a 200-status gate.

- **Generated server surface** (`src/generated/kotlin/...`) — checked-in
  OpenAPI-generated Kotlin sources mirroring the client-side shapes but
  including QUIC and SCTP keys/elements in addition to HTX.

## Source layout

```
src/
  commonMain/kotlin/borg/trikeshed/server/
    ServerContextFactory.kt      — buildServerContext(), closeServerContext()
    HtxGeneralServerAdapter.kt   — adapter: context[HtxKey] -> request dispatch
  generated/kotlin/borg/trikeshed/server/generated/
    Keys.kt                      — htx, quic, sctp AsyncContextKey re-exports
    Elements.kt                  — factory functions for each element
    SupervisorJobs.kt            — per-operation SupervisorJob factory
  commonTest/kotlin/.../
    ServerContextFactoryTest.kt  — context build, round-trip, close
    OpenApiGeneratorTddTest.kt   — generated shape validation
  jvmTest/kotlin/.../
    ServerContextFactoryJvmTest.kt              — JVM-specific context build
    HtxGeneralOpenApiContractTest.kt            — codegen + contract + drift
    HtxGeneralClientServerCompatibilityTest.kt  — client/server round-trip
```

## Key / Element / Reactor status

| Shape                       | Status   | Notes                                        |
|-----------------------------|----------|----------------------------------------------|
| Keys.htx/quic/sctp          | Active   | Generated re-exports of upstream AsyncContextKeys |
| buildServerContext()        | Active   | Opens all three elements into CoroutineContext    |
| HtxGeneralServerAdapter     | Active   | Delegates to context[HtxKey].request()           |
| ReactorSupervisor           | Partial  | SupervisorJobs.getHealth() exists but not wired  |

## Dependencies

- `:libs:common` (AsyncContextElement, AsyncContextKey, ElementState)
- `:libs:htx-client` (HtxKey, HtxElement, openHtxElement, generated types)
- `:libs:quic` (QuicKey, QuicElement, openQuicElement)
- `:libs:ngsctp` (SctpKey, SctpElement, openSctpElement)
- kotlinx-coroutines-test (test only)

## OpenAPI contract

Authoritative spec: `libs/server/openapi/htx-general.openapi.yaml`

The server adapter does NOT re-generate from the spec — it consumes the
htx-client generated types.  The server's own `src/generated/` is produced
by the same OpenAPI pipeline but includes QUIC/SCTP keys.

## Validation targets

```
./gradlew -p libs/server jvmTest --tests borg.trikeshed.server.HtxGeneralOpenApiContractTest
./gradlew -p libs/server jvmTest --tests borg.trikeshed.server.HtxGeneralClientServerCompatibilityTest
./gradlew -p libs/htx-client verifyHtxGeneralClientGeneratedSources
```
