# Plan: Cpp2 Surface Transition

## Purpose

Port Kotlin text into an expanded cpp2 spec locally, using TrikeShed as the reference surface. This remains a text/spec track until an implementation surface deserves trust.

## Current Posture

- TrikeShed Kotlin is the ideal reference shape.
- cpp2 needs work to become capable of this shape.
- `../cppfort` is still not accepted as project truth; it is downstream from the local spec, not the authority over it.

## Reality Check

- The imported Grok transcript in [conductor/grok_share_bGVnYWN5_21edd44f-9e25-434b-9bcb-2d036feee2dc.md](/Users/jim/work/TrikeShed/conductor/grok_share_bGVnYWN5_21edd44f-9e25-434b-9bcb-2d036feee2dc.md) contains strong architectural signal plus speculative generated code.
- This repo can own the Kotlin-to-cpp2 text/spec mapping and the semantic contract.
- This repo still cannot honestly mark sibling-repo files as complete without direct verification there.

## Canonical Type Mapping

| TrikeShed Kotlin | Cpp2 Canonical | Relationship |
| --- | --- | --- |
| `Join<A, B>` (pair: `.a`, `.b`) | repo-owned pair / product node | Foundation |
| `Series<T>` = `Join<Int, (Int)->T>` | `indexed<int, F>` or equivalent canonical indexed node | Core 1:1 |
| `Cursor` = `Series<RowVec>` | nested indexed projection | Columnar view |
| `RowVec` = `Series2<Any?, ()->ColumnMeta>` | row projection with explicit meta carrier | Row projection |
| `grad` surfaces under `src/jvmMain/kotlin/borg/trikeshed/grad/` | grad backend protocol | Semantic, not dense |
| manifold language proposed in transcript | cpp2 manifold layer | Must stay semantic-first |
| lowered contiguous storage | `dense_tensor` / contiguous view | Separate from semantic layer |

## Architecture

- **Front-end sugar is textual and cheap** — operators and underscore patterns are notation only
- **Early normalization** → small repo-owned canonical AST built from the template types above
- **SoN + constant propagation** does the real type/effect recovery (AA/Simple discipline)
- **Dense lowered views stay separate** from semantic tensor/series views
- **Expanded cpp2 spec** must grow until it can host the proven Kotlin text forms
- **Temporary cppfront ride** is allowed only as bootstrap compatibility, not as the design authority

## Course-Corrected Ownership

- TrikeShed owns the semantic lineage: `Join`, `Series`, `Series2`, `Cursor`, row/meta projection, and the JVM grad surfaces.
- TrikeShed now also owns the local expanded cpp2 text/spec draft.
- cppfort owns any future parser, canonical cpp2 surface, SoN lowering, and dogfood verification in its own repo if that project recovers.
- This track exists to port proven Kotlin text into that spec without narrating speculative external completion.

## Bounded Corpus

- `src/commonMain/kotlin/borg/trikeshed/lib/Series.kt`
- `src/commonMain/kotlin/borg/trikeshed/lib/Series2.kt`
- `src/commonMain/kotlin/borg/trikeshed/cursor/Cursor.kt`
- `src/commonMain/kotlin/borg/trikeshed/manifold/Manifold.kt`
- `conductor/grok_share_bGVnYWN5_21edd44f-9e25-434b-9bcb-2d036feee2dc.md`
- `conductor/tracks/cpp2-surface-transition_20260311/plan.md` — this plan
- `conductor/tracks/cpp2-surface-transition_20260311/expanded_cpp2_spec.md`
- `conductor/tracks.md`

## Slices

- `cpp2surf-01` ✅ transcript intake + signal/noise course correction in local `/conductor/`
- `cpp2surf-02` ✅ Kotlin-first manifold/spec posture captured
- `cpp2surf-03` in progress: expanded cpp2 spec text for manifold/coordinates/atlas
- `cpp2surf-04` pending: broader Kotlin text port beyond the manifold slice
- `cpp2surf-05` pending: verify any sibling-repo dogfood claims only after an actual bounded slice runs there

## Acceptance Gates

- Do not collapse semantic and dense layers into one type family.
- Do not accept generated code dumps as completed implementation.
- Do not claim cppfort completion from TrikeShed alone.
- Keep the spec vocabulary small enough to normalize early and feed SoN cleanly.
- Treat Kotlin text as the reference surface and cpp2 as the adapting target.

## Verification

- Read the bounded TrikeShed corpus named above and reconcile it against the imported transcript.
- Ensure `tracks.md` does not overstate progress inside sibling repos.
- Ensure any future external completion claim is backed by direct inspection in that repo before closure.
