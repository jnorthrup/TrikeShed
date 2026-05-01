# libs/htx-client

HTTP (HTX) client AsyncContextElement for TrikeShed.  An `HtxElement` wraps a
suspend request handler and participates in the CoroutineContext lifecycle
(created -> open -> closed).  The module also carries OpenAPI-generated client
artifacts for the `htx-general` service contract.

## What it is (mechanically)

- **HtxElement** — an `AsyncContextElement` that holds a typed
  `HtxRequestHandler = suspend (HtxClientRequest) -> HtxClientMessage`.
  Looked up in a `CoroutineContext` via the companion `HtxKey`.
  `request()` requires `ElementState.OPEN`.

- **Aria2Switches / Aria2Help** — data class and help-text object that model
  aria2c download flags (`-Z`, `-c`, `-x`, `-j`, `-s`, `-d`).  Used by
  combined-client and server for multi-protocol transfer dispatch.

- **Generated client surface** (`src/generated/kotlin/...`) — checked-in,
  OpenAPI-generated Kotlin sources.  Regenerated from
  `libs/server/openapi/htx-general.openapi.yaml` via the
  `openApiGenerateHtxGeneralClient` Gradle task.  **Do not edit by hand.**

## Source layout

```
src/
  commonMain/kotlin/borg/trikeshed/htx/client/
    HtxElement.kt           — HtxElement, HtxKey, HtxClientRequest/Message,
                               defaultHtxRequestHandler, openHtxElement,
                               Aria2Switches
    Aria2Help.kt            — aria2c -h emulator for TDD / dev tooling
  generated/kotlin/borg/trikeshed/htx/client/generated/
    Keys.kt                 — re-exports HtxKey + operationId constant
    Elements.kt             — factory: openHtxElement()
    SupervisorJobs.kt       — per-operation SupervisorJob factory
    api/HtxGeneralApi.kt    — HtxGeneralApi interface + DefaultHtxGeneralApi,
                               HtxGeneralApiContract (GET /health)
    infrastructure/
      GeneratedRequest.kt   — HttpMethod enum, GeneratedRequest(method, path)
    model/HealthStatus.kt   — HealthStatus(body), ok boolean
  commonTest/kotlin/.../
    HtxElementTest.kt             — CoroutineContext lookup correctness
    HtxElementTddTest.kt          — lifecycle, request dispatch, Aria2Switches
    HtxOpenApiGeneratorTddTest.kt — generated file presence + content shape
    GeneratedHtxGeneralClientTest.kt — contract check against GET /health
    Aria2HelpTddTest.kt           — help text coverage
```

## Key / Element / Reactor status

| Shape            | Status   | Notes                                        |
|------------------|----------|----------------------------------------------|
| HtxKey           | Active   | `AsyncContextKey<HtxElement>` companion      |
| HtxElement       | Active   | Lifecycle: CREATED -> OPEN -> CLOSED         |
| ReactorSupervisor| None     | SupervisorJobs per-operation only            |

HtxElement `request()` enforces `requireState(OPEN)`; `open()` is idempotent.

## Dependencies

- `:libs:common` (AsyncContextElement, AsyncContextKey, ElementState)
- kotlinx-coroutines (SupervisorJob in generated code)
- No transport-layer deps (QUIC, SCTP) — purely HTTP request/response

## OpenAPI code generation

```
./gradlew -p libs/htx-client openApiGenerateHtxGeneralClient
./gradlew -p libs/htx-client verifyHtxGeneralClientGeneratedSources
```

Generated outputs are checked in for review and must be committed after
regeneration.  Verification is non-mutating.
