# libs/polyglot — Boundary Audit & Path to Stable

## Boundary Audit

### Clean boundaries (no issues)
- **MlirTaxonomy**: Pure data. 15 dialects, ~60 canonical ops, nodeToMlir()
  is a total function over all 35 NodeKinds. No side effects. No global state.
  Exhaustively tested in MlirTaxonomyContractTest (35+ assertions).
- **SourceFragment**: Clean data model. toRowVec() projection is deterministic.
  flatten() is a lazy Sequence. NodeKind enum is complete and stable.
- **LangFingerprint**: TypeEvidence-based fingerprinting is sound. RowVec
  projection matches TypeEvidence column schema. Confidence formula is well-
  defined (normalized inverse delta over 15 numeric counters).

### Boundary concerns (needs attention)

1. **LangRegistry is a global mutable singleton**
   - `LangRegistry` uses a `mutableListOf<LangEntry>()` with no thread safety.
     `register()` and `classifyAll()` can race if called from multiple coroutines.
   - `reset()` exists for test isolation but is dangerous in production.
   - **Fix**: Make LangRegistry an interface. Provide a `ConcurrentLangRegistry`
     and a `TestLangRegistry`. Inject the registry rather than using a singleton.
     Alternatively, freeze the registry after initialization (register → seal).

2. **LinguaPipeline: stages are documented as coroutine-fanout but not implemented**
   - Stage 0 docs say "All N LangClassifiers run concurrently under
     ParseScope.fanoutParsers()" but `classifyAll()` runs synchronously:
     `entries.map { it.classify(source) }`.
   - `LangParsers` is another mutable global singleton (`mutableMapOf`).
   - `pipeline()` is a suspend function but doesn't actually use coroutines
     internally (all stages run sequentially).
   - **Fix**: Either implement the documented fanout with coroutineScope/async,
     or update the documentation to match the sequential implementation.
     Decide which is correct before stabilizing.

3. **lower() is a stub**
   - `fun lower(cursor: Cursor): Unit = Unit` — the entire Stage 5 is a no-op.
   - The MLIR IR generation, ORC JIT compilation, and MlirOrcBuilder
     integration mentioned in comments do not exist.
   - **Fix**: Either implement lowering or remove the stub and document Stage 5
     as future work. Don't leave a silent no-op in the pipeline.

4. **Nars3PolyglotBridge: lossy conversion**
   - `fragmentToAtom()` converts all NodeKinds to `NarsiveElementKind.UNKNOWN`.
     No actual semantic mapping from NodeKind → NarsiveElementKind.
   - `ChannelizedAtom` is used when `children.size > 0`, but ChannelizedAtom
     and LocalAtom have identical implementations in kursive.
   - Default budget is hardcoded (0.5, 0.5, 0.5) with no configuration.
   - **Fix**: Define a NodeKind → NarsiveElementKind mapping table. Allow
     budget configuration. Revisit when LocalAtom/ChannelizedAtom diverge.

5. **confidence() metric is untested with real languages**
   - The TypeEvidence-based confidence score works in theory but has not been
     validated against actual source files in 13 registered languages.
   - Edge case: all-zero TypeEvidence rows (empty source) produce confidence=1.0
     (division by max(1.0, 0.0) = 1.0, diff = 0.0, score = 1.0).
   - **Fix**: Add integration tests with real source samples. Handle the
     empty-source edge case (return confidence 0.0 for empty input).

6. **SourceFragment.toRowVec() hardcodes 6 columns**
   - The RowVec projection has a fixed schema (lang, spanStart, spanEnd, kind,
     name, deducedType) that doesn't include NodeMeta fields or child count.
   - **Fix**: Either extend the schema or document the projection as a summary
     view (not the full fragment).

7. **classify() in LinguaPipeline samples fragment.name, not fragment text**
   - `classify(ast)` runs TypeEvidence on `sf.name ?: ""` instead of the actual
     source span text. This means classification only fingerprints names, not
     the full source of each fragment.
   - **Fix**: Classify using the source span text, not the name. The pipeline
     receives `source: Series<Char>` but classify() doesn't use it.

## Integration Steps

1. **Replace LangRegistry singleton with injectable registry**: Allow test
   isolation and thread safety. Add seal() for production freeze.
2. **Implement or remove Stage 5**: Decide whether MLIR lowering belongs in
   this module or a downstream module. Remove the stub either way.
3. **Implement or document Stage 0 fanout**: Either add coroutineScope/async
   to classifyAll() or update docs to match sequential reality.
4. **Fix empty-source confidence edge case**: Return 0.0 confidence for empty
   or very-short inputs.
5. **Fix classify() to use source span text**: Not fragment names.
6. **Validate confidence with real language samples**: Add integration tests
   with representative Kotlin, Python, Rust, etc. source files.
7. **Define NodeKind → NarsiveElementKind mapping**: Make the NARS3 bridge
   semantically meaningful, not just syntactic.

## Path to Stable

- [ ] LangRegistry injection (de-singleton)
- [ ] Stage 5 decision (implement, move, or remove)
- [ ] Stage 0 fanout decision (implement or document sequential)
- [ ] Empty-source confidence fix
- [ ] classify() source span fix
- [ ] Real-language classification validation tests
- [ ] NodeKind → NarsiveElementKind mapping table
- [ ] SourceFragment.toRowVec schema review (include NodeMeta?)
- [ ] End-to-end pipeline test: source text → detect → parse → classify → unify → map
