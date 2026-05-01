# libs/polyglot

Language/parse taxonomy bridge. Provides speculative language detection via
TypeEvidence fingerprinting, a universal AST model (SourceFragment), a
five-stage pipeline from source text to MLIR-ready region blocks, and a bridge
to the NARS3 reasoning VM.

## What It Is (Mechanically)

Five components:

1. **SourceFragment** — Universal AST node. Carries `lang` (LangId), `span`
   (Twin<Int>), `kind` (NodeKind — 35 variants covering structural, control
   flow, expression, and type nodes), `evidence` (TypeEvidence fingerprint),
   `children` (nested SourceFragments), and `meta` (NodeMeta — visibility,
   mutability, lifetime, async/generic/extern flags). Projects to `RowVec`
   via `toRowVec()`. Flattens depth-first to `Sequence<RowVec>`.

2. **LinguaTaxonomy** — Speculative language classification. Each registered
   language has a `LangFingerprint` (TypeEvidence row over its keyword corpus)
   and a `LangClassifier` (scans live source → TypeEvidence row). Confidence
   is computed as normalized inverse delta between live and reference
   TypeEvidence counters (15 numeric columns). `LangRegistry` is a global
   append-only registry. `classifyAll()` runs all classifiers and ranks by
   confidence.

3. **LinguaPipeline** — Five-stage funnel from source text to MLIR:
   - Stage 0: DETECT — speculative classification via `LangRegistry.bestMatch()`
   - Stage 1: PARSE — winning LangParser → UniversalAst → SourceFragment tree
   - Stage 2: CLASSIFY — TypeEvidence scan per fragment
   - Stage 3: UNIFY — SourceFragment → DescriptorFragment tree
   - Stage 4: MAP — DescriptorFragment → Cursor<RowVec> via rowVecTree()
   - Stage 5: LOWER — Cursor → MLIR ops (stub: `fun lower(cursor: Cursor): Unit`)
   The end-to-end `pipeline()` function chains all stages under a single
   confidence floor.

4. **MlirTaxonomy** — MLIR dialect and operation model. 15 dialects
   (builtin, func, arith, math, scf, cf, memref, tensor, linalg, affine,
   llvm, gpu, vector, spirv, stablehlo). Canonical op constants in
   `FuncOps`, `ArithOps`, `MathOps`, `ScfOps`, `CfOps`, `MemrefOps`,
   `TensorOps`, `LinalgOps`, `AffineOps`. `nodeToMlir(NodeKind)` maps each
   of the 35 NodeKinds to zero or more candidate MlirOps.

5. **Nars3PolyglotBridge** — Bridges SourceFragment trees to NARS3 Machine
   atoms. Each fragment becomes a `LocalAtom` (leaf) or `ChannelizedAtom`
   (has children). Walks the AST depth-first to produce an atom series for
   arena chain construction.

## Source Layout

```
src/commonMain/kotlin/borg/trikeshed/polyglot/
  SourceFragment.kt       — NodeKind enum (35 kinds), NodeMeta, SourceFragment
                              (span, evidence, children, toRowVec, flatten),
                              UniversalAst (toCursor), LangParser interface

  LinguaTaxonomy.kt       — LangId enum (13 languages), LangFingerprint,
                              LangClassifier (fun interface), ClassificationResult,
                              confidence() function (normalized inverse delta),
                              LangEntry, LangRegistry (append-only, classifyAll,
                              bestMatch, byExtension, byId)

  LinguaPipeline.kt       — Five-stage pipeline: detect, parse, classify,
                              unify, mapRegions, lower. LangParsers registry.
                              End-to-end pipeline() suspend function.

  MlirTaxonomy.kt         — MlirDialect enum (15 dialects), MlirOp data class,
                              canonical op objects (FuncOps, ArithOps, MathOps,
                              ScfOps, CfOps, MemrefOps, TensorOps, LinalgOps,
                              AffineOps), nodeToMlir() mapping (35 NodeKinds),
                              mlirRelevantKinds (NodeKinds that produce MLIR ops)

  nars3/
    Nars3PolyglotBridge.kt — fragmentToAtom(), astToArenaChain(). Bridges
                                SourceFragment → Nars3Atom with default budget.

src/commonTest/kotlin/borg/trikeshed/polyglot/
  MlirTaxonomyContractTest.kt — Full contract test for nodeToMlir mapping
                                   (35 NodeKinds, all 15 dialects, all op objects)
```

## Key/Element Pattern Status

This module does NOT use `AsyncContextKey/AsyncContextElement` or
`ElementState` lifecycle. It uses:

- **TypeEvidence** as the core classification primitive (character-class
  fingerprinting without reflection or language-specific dispatch).
- **LangRegistry** as a global singleton registry (not coroutine-scoped).
- **CoroutineScope/SupervisorJob** mentioned in documentation as the intended
  fanout mechanism for Stage 0 classification, but the current implementation
  runs `classifyAll()` synchronously (no actual coroutine fanout yet).

No Key/Element/Reactor pattern in this module. The NARS3 bridge creates atoms
from the kursive module's Nars3Machine, but polyglot itself is pure data
classification.

## Dependencies

- **TrikeShed core**: `lib` (Series, Join, Twin, CharSeries, j infix, alpha,
  toSeries, plus, size, get, zip), `collections` (s_ builder),
  `common.TypeEvidence`, `cursor` (ColumnMeta, RowVec, joins),
  `isam.meta` (IOMemento), `parse.interop` (DescriptorFragment, rowVecTree)
- **libs:kursive** (declared build dependency):
  NarsiveElement, NarsiveElementKind, nars3.Nars3Budget, nars3.Nars3Atom,
  nars3.LocalAtom, nars3.ChannelizedAtom, nars3.Nars3Machine
- **kotlin.math.abs** (for confidence computation)
- Build: `../../gradle/macros/trikeshed-lib.gradle`
  + `commonMainImplementation(project(":libs:kursive"))`
