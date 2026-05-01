# libs/server — boundary audit & path to stable

## Boundary audit

### Clean boundaries
- [x] ServerContextFactory is a pure composition layer — no business logic
- [x] closeServerContext() closes in reverse order (htx -> sctp -> quic)
- [x] closeServerContext() is idempotent (checks OPEN before close)
- [x] HtxGeneralServerAdapter is a thin adapter — extracts HtxKey and delegates
- [x] Generated Keys/Elements/SupervisorJobs mirror the OpenAPI contract shape

### Boundary concerns
- [ ] HtxGeneralServerAdapter.execute() uses `GeneratedRequest` and returns
      `HtxClientMessage` but references `DefaultHtxGeneralApi` and
      `HtxGeneralApi` (from htx-client generated code) WITHOUT importing them
      — the file as checked in is missing import statements for
      `borg.trikeshed.htx.client.generated.api.*` and
      `borg.trikeshed.htx.client.generated.infrastructure.*`.  This will not
      compile unless the IDE auto-resolves star imports.
- [ ] The adapter's `client()` method checks `response.status == 200` and throws
      on any non-200 — this is a policy decision baked into infrastructure
      rather than surfaced to the caller.  404/405 should arguably be returned
      as structured errors, not IllegalStateException.
- [ ] ServerContextFactory opens elements sequentially (quic -> sctp -> htx)
      with no error recovery — if sctp.open() fails, quic is left OPEN and
      never closed.  Needs try/catch with rollback or a transactional open.
- [ ] No ACTIVELY_DRAINING state is used during closeServerContext — in-flight
      requests on the HTX element could be cut off mid-flight.
- [ ] Generated server Keys/Elements duplicate the htx-client generated code
      (both have Keys.htx, Elements.htx()).  The server adds quic/sctp but the
      htx entries are identical — consider a shared generated module.

### Integration edges
- [ ] libs/htx-client provides the generated API contract types consumed here
- [ ] libs/quic and libs/ngsctp provide transport elements — server only uses
      their Key/Element/open functions, never their transport logic directly
- [ ] HtxGeneralOpenApiContractTest reaches into libs/htx-client/build.gradle.kts
      and libs/htx-client/README.md to validate documentation consistency —
      cross-module test dependency
- [ ] HtxGeneralOpenApiContractTest.runGradle() shells out to ./gradlew —
      requires a fully-built repo, not suitable for fast unit test suites

## Path to stable

1. **Fix missing imports in HtxGeneralServerAdapter.kt** — add explicit imports
   for GeneratedRequest, HtxGeneralApi, DefaultHtxGeneralApi from the htx-client
   generated packages.
2. **Transactional open in buildServerContext()** — wrap element opens in
   try/catch; on failure, close any already-opened elements before rethrowing.
3. **Surface HTTP errors from adapter.client()** — return a Result or sealed
   class instead of throwing on non-200, so callers can handle 404/405
   gracefully.
4. **DRAINING lifecycle** — add a drain step to closeServerContext that sets
   elements to DRAINING (or ACTIVE->DRAINING) and awaits in-flight requests
   before final close.
5. **Wire SupervisorJobs** — the generated SupervisorJobs.getHealth() is unused.
   Attach it to the adapter's coroutine scope so per-operation cancellation
   works.
6. **Reduce cross-module test reach** — HtxGeneralOpenApiContractTest reads
   build.gradle.kts and README.md from htx-client.  Consider moving the
   contract validation into a shared integration test module.
7. **Separate generated code** — evaluate merging server-side generated
   Keys/Elements/SupervisorJobs into htx-client's generated output (with a
   server-specific superset) to eliminate duplication.
