# libs/cmc-generated

Auto-generated CoinMarketCap API client + server adapter. Output of
`libs/openapi` code generator, driven by the CMC endpoint-overview OpenAPI spec.

**Repository policy: all files in this module are generated. Do not edit by hand.
Regenerate via `./gradlew generateCmcSources`.**

## What It Is (Mechanically)

A **static Kotlin module** with no runtime state. Contains:

1. An API interface + default HTTP-callable implementation
2. A server-side request router (adapter)
3. HTTP infrastructure types (method enum, request data class)
4. Key/Element/Reactor scaffolding (currently empty placeholders)
5. Response model scaffolding (currently empty — no response schemas in the spec)

## Source Layout

All sources live under `src/generated/kotlin/borg/trikeshed/cmc/`.

| File | Role |
|------|------|
| `api/CoinMarketCapAPIApi.kt` | `interface CoinMarketCapAPIApi` with one method: `suspend fun cmcEndpointOverview(): CharSequence`. `DefaultCoinMarketCapAPIApi` takes a `suspend (GeneratedRequest) -> CharSequence` call function. `CoinMarketCapAPIApiContract` object holds per-operation `GeneratedRequest` constants. |
| `infrastructure/GeneratedRequest.kt` | `enum class HttpMethod { GET, POST, PUT, DELETE, PATCH }` + `data class GeneratedRequest(method, path, queryParams, body, operationId)`. |
| `CoinMarketCapAPIServerAdapter.kt` | Server-side router: `CoinMarketCapAPIServerAdapter(context: CoroutineContext)` dispatches on `request.operationId`. Currently returns `501 Not Implemented` for the single operation (`cmcEndpointOverview`). Contains `Contract` object with operation constants. |
| `ServerMessage.kt` | `data class ServerMessage(status, headers, body)` with `isSuccess` helper. |
| `model/CoinMarketCapAPIModels.kt` | Placeholder — no response schemas resolved from the spec. |
| `Keys.kt` | `object Keys` — empty. No `x-trikeshed-context` server bindings declared in the CMC spec. |
| `Elements.kt` | `object Elements` — empty. Same reason. |

## Key/Element/Reactor Status

### Keys

- **Empty placeholder.** The CMC spec has no `x-trikeshed-context` extension,
  so the generated `Keys` object has no `AsyncContextKey<E>` entries.
- **To activate:** Add `x-trikeshed-context` bindings to the CMC OpenAPI spec
  (in `libs/cmc/endpoint-overview/openapi/coinmarketcap.openapi.yaml`) and
  regenerate.

### Elements

- **Empty placeholder.** Same reason as Keys.
- **To activate:** Once bindings are declared, `Elements` will contain
  `suspend fun` factory methods that call the `open` function on each element.

### ReactorSupervisor

- **Not generated.** No operations in the CMC spec are marked
  `x-trikeshed-supervisor: true`, so no `SupervisorJobs` object was emitted.
- If any operation needs independent coroutine failure isolation, mark it
  `x-trikeshed-supervisor: true` in the spec and regenerate.

## Dependencies

- `borg.trikeshed.context.AsyncContextKey` — imported in Keys (currently unused)
- `kotlin.coroutines.CoroutineContext` — used by server adapter
- **No dependency on `kotlinx.coroutines`** at runtime (no SupervisorJob generated)
- **No dependency on networking libraries** — HTTP transport is injected via
  the `call` lambda in `DefaultCoinMarketCapAPIApi`

## What The Spec Covers

The source spec (`libs/cmc/endpoint-overview/openapi/coinmarketcap.openapi.yaml`)
defines a single operation:

- `GET /api/documentation/pro-api-reference/endpoint-overview` — the CMC API
  documentation landing page.

This is a **guide-contour** spec (documentation-oriented), not a data API spec.
It has no request/response schemas, no parameters, and no security requirements.
The generated code reflects this minimalism.

## Generation Command

```
./gradlew generateCmcSources
```

Input:  `libs/cmc/endpoint-overview/openapi/coinmarketcap.openapi.yaml`
Output: `libs/cmc-generated/src/generated/kotlin/borg/trikeshed/cmc/`
