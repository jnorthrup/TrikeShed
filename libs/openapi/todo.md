# libs/openapi — TODO

## Intent
OpenAPI spec parser, resolver, and Kotlin code generator. Reads OpenAPI YAML/JSON, resolves $ref pointers, produces resolved schema model, generates Key/Element/SupervisorJob Kotlin source for server and client stubs. KMP full.

## Status: BETA (parser solid, code generation functional, Reactor pattern partially wired)

## Pure boundary audit

### Keys (need creation for call pipeline)
- `OpenApiCallPipeline` has its own `SupervisorJob` + `CoroutineScope` — not an AsyncContextElement
  - [ ] Make call pipeline sessions extend `AsyncContextElement`
  - [ ] Use the generated Keys/Elements from this module's own codegen

### Elements (stateful)
- `OpenApiCallPipeline` — manages concurrent call fan-out with `coroutineScope { async { } }`
  - [ ] Extend `AsyncContextElement` instead of ad-hoc SupervisorJob
  - [ ] Lifecycle: CREATED → OPEN (parse spec) → ACTIVE (accept calls) → DRAINING → CLOSED

### Statics → correct
- `ResolvedSchema` sealed interface — clean taxonomy ✓
- `ResolvedOperation`, `ResolvedParameter`, etc. — pure data ✓
- `ContextBinding` — maps Trikeshed x-extension to Key/Element FQN ✓
- `TrikeshedContext` — parsed x-trikeshed-context extension ✓
- `OpenApiRawParser` object — pure parser ✓

### Code generation
- Generates `Keys.kt`, `Elements.kt`, `SupervisorJobs.kt` for server and client modules
- The generated code follows the Key/Element/SupervisorJob pattern from htx-client template ✓
- [ ] Generated SupervisorJobs should use `AsyncContextElement.supervisor` instead of bare `SupervisorJob()`

### Reactor integration
- `OpenApiReactorModel` — maps OpenAPI operations to reactor context bindings ✓
- `isSupervisor` flag on `ResolvedOperation` — marks operations that need their own SupervisorJob branch
- [ ] Generate `ReactorSupervisor.launchBranch()` calls for supervisor operations

## Integration partners
- **htx-client**: openapi generates client stubs that use HtxElement context
- **server**: openapi generates server adapters (HtxGeneralApi, etc.)
- **couch**: openapi can generate CouchDB service stubs (relaxfactory/ already does this manually)

## Path to stable
1. Make `OpenApiCallPipeline` extend `AsyncContextElement`
2. Use `ElementState` instead of ad-hoc state
3. Ensure generated code uses `AsyncContextElement` base for SupervisorJobs
4. Add test: generated Keys/Elements compile and pass lifecycle tests
5. Integration test: OpenAPI spec → codegen → compile → run call pipeline
