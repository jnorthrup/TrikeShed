# libs/cmc-generated — TODO (Boundary Audit)

## Status: Generated Skeleton — Needs Spec Enrichment Before Key/Element Migration

This module is pure generated code with empty Keys/Elements because the source
spec has no `x-trikeshed-context` bindings. The meaningful work is in the spec,
not in this module.

---

## Spec Enrichment (in libs/cmc/)

- [ ] **Add real CMC API operations to the spec** — The current spec defines
      only one guide-contour operation (endpoint overview). Add the actual CMC
      Pro API endpoints: `/v1/cryptocurrency/listings/latest`,
      `/v1/cryptocurrency/quotes/latest`, `/v1/exchange/map`, etc.
- [ ] **Define response schemas** — No response schemas are declared, so
      `CoinMarketCapAPIModels.kt` is empty. Add JSON response schemas for each
      endpoint so the generator can produce typed data classes.
- [ ] **Define request parameters** — No query/header parameters declared.
      CMC API uses `X-CMC_PRO_API_KEY` header + query params like `start`,
      `limit`, `convert`, `id`, `slug`.
- [ ] **Add security schemes** — CMC uses API key authentication. Declare
      `securitySchemes` in the spec so the generator can render auth headers.

---

## Key/Element Activation

- [ ] **Add `x-trikeshed-context` client bindings** — Once the spec has real
      operations, define at least one client binding (e.g., name: `cmc`,
      key: `borg.trikeshed.cmc.Keys.cmc`, element: `...CmcElement`,
      open: `...openCmc`). This will populate `Keys.kt` and `Elements.kt`.
- [ ] **Add `x-trikeshed-context` server bindings** — If server-side routing
      is needed, add server bindings similarly.
- [ ] **Mark supervisor operations** — If any CMC operation needs independent
      failure isolation (e.g., a long-polling or batch endpoint), mark it
      `x-trikeshed-supervisor: true` to generate a `SupervisorJobs` object.

---

## Generator Improvements (in libs/openapi)

- [ ] **Server adapter should return 501 with actionable message** — Currently
      the adapter returns `ServerMessage(501, body = "cmcEndpointOverview not implemented")`.
      Once context bindings exist, this should route through the reactor context.
- [ ] **`CoinMarketCapAPIApi` return types should be typed** — Currently returns
      `CharSequence` for all operations. Once response schemas exist, the generator
      should produce typed return types (e.g., `ListingsLatestResponse`).
- [ ] **Contract objects should include query param templates** — Currently
      `CmcEndpointOverview.request` has empty `queryParams`. Once parameters
      are declared in the spec, the contract should pre-populate param names.

---

## Integration Steps

1. Enrich `coinmarketcap.openapi.yaml` with real CMC Pro API endpoints,
   parameters, response schemas, and security schemes.
2. Add `x-trikeshed-context` bindings for at least one client-side context.
3. Regenerate: `./gradlew generateCmcSources`.
4. Verify generated `Keys.kt` has `AsyncContextKey` entries.
5. Verify generated `Elements.kt` has `suspend fun` factories.
6. Verify generated `CoinMarketCapAPIModels.kt` has response data classes.
7. Wire `DefaultCoinMarketCapAPIApi` into the application's coroutine context
   with an HTTP transport lambda.

---

## Path to Stable

1. Enrich the spec with real API surface.
2. Add context bindings.
3. Regenerate and validate.
4. Write an integration test that uses the generated client against a mock
   HTTP server.
5. Consider adding a `SupervisorJobs` object for batch/polling operations.
