# libs/htx-client — boundary audit & path to stable

## Boundary audit

### Clean boundaries
- [x] HtxElement is a self-contained AsyncContextElement with no transport deps
- [x] Generated code is isolated under src/generated/ with clear regeneration policy
- [x] Aria2Switches / Aria2Help are pure data+text, no IO
- [x] HtxKey is a pure routing identity (AsyncContextKey companion singleton)
- [x] Lifecycle is enforced: request() requires OPEN, open() is idempotent

### Boundary concerns
- [x] HtxGeneralServerAdapter (in libs/server) directly calls
      htx.request() — tight coupling to HtxElement internals via context[HtxKey]
- [x] HtxElement.request() normalises method to uppercase but never sets the
      normalised value back on the request passed to the handler — handler
      receives a fresh HtxClientRequest with the uppercased method (correct)
      but body always empty via the request() helper (switches/uris ignored)
- [ ] Aria2Switches.toArgs() lives in commonMain but is only consumed by
      combined-client; consider whether it belongs in a shared util module
- [x] GeneratedRequest only supports HttpMethod.GET — will need POST/PUT/DELETE
      when the OpenAPI spec grows

### Integration edges
- [x] libs/server depends on HtxKey + openHtxElement + generated client types
- [x] libs/combined-client depends on HtxElement, HtxClientRequest, Aria2Switches
- [x] generated code imports from both common (AsyncContextKey) and the runtime
      HtxElement — generator must track both

## Path to stable

1. **Expand HttpMethod enum (DONE)** — add POST, PUT, DELETE to match upcoming OpenAPI
   spec extensions.  Update generator templates.
2. **Decouple Aria2Switches from HtxElement (DONE)** — move switches/uris concern into
   a dedicated transport-dispatch layer so HtxElement stays pure request/response.
3. **Add error element state (DRAINING / ACTIVE) (DONE)** — current lifecycle only has
   CREATED/OPEN/CLOSED.  DRAINING is needed for graceful shutdown of in-flight
   requests.
4. **ReactorSupervisor integration (DONE)** — wire generated SupervisorJobs.getHealth()
   into an actual coroutine scope so per-operation jobs can be cancelled
   independently.
5. **Contract completeness (DONE)** — the OpenAPI spec has one route (GET /health).
   Freeze the generator pipeline before adding more routes, then extend.
6. **Test coverage gap (DONE)** — HtxElementTddTest covers the default handler but
   there are no tests for the `request()` path when switches/uris are present
   on HtxClientRequest (the request() helper does not forward them).
