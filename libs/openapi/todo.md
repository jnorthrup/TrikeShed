# libs/openapi — TODO (Boundary Audit)

## Boundary Status: Generator (Pure) — No Key/Element Migration Needed

The openapi module is a **pure code generator**. It has no runtime singletons,
no stateful objects, and no enums that should become Keys. The Key/Element
pattern is *output* of this generator, not something it consumes internally.

That said, there are structural improvements worth tracking:

---

## Spec Completeness

- [ ] **YAML `$ref` inside `x-trikeshed-context`** — `parseTrikeshedContext()` uses
      regex on raw spec text rather than the parsed tree. This breaks if the
      context block is behind a `$ref`. Should parse from the resolved JsonMap.
- [ ] **`allOf` merging is shallow** — `resolveSchemaImpl` flattens `allOf` by
      concatenating properties, but ignores `required` set union and
      `additionalProperties` merging.
- [ ] **`anyOf`/`oneOf` produce `Variant(Generic)`** — no actual variant
      resolution. Needs a strategy (sealed class? tagged union?) for generated code.
- [ ] **`format` mapping is incomplete** — `toKotlinType()` ignores most string
      formats (date-time, uuid, etc.) and maps everything to `CharSequence`. Should
      at least annotate or use value classes.

---

## Generator Output Quality

- [ ] **Response model deduplication** — `renderClientModels` uses
      `distinctBy { it.first }` which silently drops duplicate-named schemas.
      Should warn or merge.
- [ ] **Query parameter encoding** — `toQueryParamBlock()` uses `.toString()`
      with no URL encoding. Will break on special characters.
- [ ] **Server adapter dispatch** — `renderServerAdapter` uses only
      `bindings.firstOrNull()` for the reactor context lookup. Should match by
      operationId or tag, not just take the first binding.
- [ ] **No error response models** — Only success schemas (2xx) are materialised.
      Error response types are discarded.
- [ ] **No authentication wiring** — `SecurityRequirement` is resolved but never
      rendered into generated client code (no API key header injection, etc.).

---

## Call Pipeline

- [ ] **`speculativeSignalBurndown` is structurally identical to `speculativeGapBurndown`**
      with a lighter parse step. Consider unifying via a type parameter or
      pipeline-stage interface to reduce ~150 lines of near-duplicate code.
- [ ] **`constructiveSupervisorCall` creates a new SupervisorJob per call** —
      acceptable for batch pipelines but should document the lifecycle contract.
- [ ] **Channel cancellation in `finally` blocks** — The fan-out pipelines cancel
      all channels in `finally`, but don't `await` worker completion. On failure,
      in-flight work may be lost silently.

---

## Integration Steps

- [ ] **Wire `GenerateSources` into Gradle build** — Currently a bare `main()`.
      Should be a Gradle task with up-to-date checking (input spec hash -> output
      file tree).
- [ ] **Add `x-trikeshed-context` bindings to Kraken/CMC/Robinhood specs** —
      All three spec families currently have *no* context bindings, so generated
      Keys/Elements are empty placeholders. Define at least one client binding
      per spec to validate the full pipeline.
- [ ] **End-to-end generation test** — Add a test that runs
      `renderAllClientSources` + `renderAllServerSources` on each real spec
      (Kraken REST, CMC, Robinhood) and asserts non-empty output + compilability.

---

## Path to Stable

1. Fix `parseTrikeshedContext` to read from resolved tree, not regex on raw text.
2. Add at least one `x-trikeshed-context` binding to each spec and regenerate.
3. Unify the two near-identical burndown pipelines.
4. Wire into Gradle as a proper codegen task.
5. Add response error model generation.
6. Add authentication header rendering.
