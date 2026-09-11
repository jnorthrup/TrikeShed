# Columnar semantics in the current Cursor algebra

The recovered execution path is `Cursor.pivot(...).groupBy(..., reducer)`, with column projection, exclusion, stable ordering and explicit-domain resampling. It reads the current `Cursor = Series<RowVec>` directly. No Columnar checkout, Maven artifact, old `Vect0r`/`Pai2` types or compatibility package is required to build or execute these operations.

## Source and type mapping

Inspected [jnorthrup/columnar at 0534034f1cce5e3c633e4217354559aa15104447](https://github.com/jnorthrup/columnar/tree/0534034f1cce5e3c633e4217354559aa15104447), including the history of Cursor, Clusters, Categories and their tests. The 2025-05-14/16 commits `c9e1fad`, `4084566`, `2f144c5` and 2026-04-12 `0534034` explain the surviving source/test organization. Both `src/test/java` and `src/test/kotlin` contain Kotlin; duplicate filenames do not imply duplicate active coverage. The historical checkout used for inspection lives outside this project and is not an input to any test or benchmark.

Existing TrikeShed mappings were checked before adding code:

| Historical shape | Current shape |
|---|---|
| `Pai2<A,B>`, `t2` | `Join<A,B>`, `j` |
| `Vect0r<T>`, `Vect02<A,B>` | `Series<T>`, `Series2<A,B>` |
| `Cursor`, `RowVec` | Existing aliases in `cursor/Cursor.kt`; row cells carry `ColumnMeta↻` |
| `Scalar` / context supplier | `ColumnMeta` / `ColumnMeta↻`; type and child schema stay attached |
| Primitive column backing names | Existing `CursorDoubleSeries`, `CursorLongBackingSeries`, `CursorIntSeries`, `CursorFloatSeries` aliases in `lib/ColumnarAlias.kt` |
| Binary value reducer | Existing `RowReducer` in `lib/Series.kt` |

The primitive aliases were introduced in TrikeShed commit `86a275c5d9ec61b436e1bc9844f6a7186f8203fe`; current definitions retain those mappings. Its earlier Cursor `where/orderBy/take/drop/project` implementation was also inspected. Existing Series filtering/ranges and current Cursor selection cover those operations without resurrecting another API family. Searches across available git refs found no historical `typealias Vect0r`, `typealias Scalar`, or `Cursor.pivot` implementation in TrikeShed.

## Function inventory and disposition

Paths in the first column are relative to Columnar's `src/main/java/cursors`.

| Historical function family | Current disposition |
|---|---|
| `Cursor.kt`: `at(Int)`, `at(IntRange)` | Existing `cursor[i]` / `row(i)` and `cursor[range]` / `range(range)`. Preserve current zero-based row indexing. Do not reproduce old negative-index arithmetic or range off-by-one defects. |
| `get(IntArray/Iterable/vararg Int)` | Existing `select(*indices)` projects columns; `cursor[IntArray]` remains the generic Series row selection. A single `cursor[i]` is a row, not the historical single-column operation. |
| `ByColName.kt`, `NegateColumn.kt`: name projection, ordinal lookup, exclusion | Existing `select`, `col`, `columnNames`; repaired CharSequence content equality. `without(vararg names)` and two inline-marker exclusions preserve retained order. Unknown projected names fail rather than silently disappear. Use `without` for arbitrary exclusion cardinality. |
| `Cursor.kt`: `pivot(lhs, axis, fanOut)` | Implemented on current Cursor in `Pivot.kt`. First-seen compound keys, original row count, repeated fanout columns, null sparse cells, generated column names and row-specific metadata. Compose `groupBy` for aggregation. |
| `Clusters.kt`: `group`, reducer overload, `groupClusters`, `keyClusters`, `mapClusters` | Existing `groupBy` repaired; `groupClusters` provides a Series of row-index arrays. Structural key equality, first-seen group order, input order within groups. Both generic and split RowVec work. No duplicate `group` name or externally mutable index-map protocol added. |
| `Cursor.kt`: `ordered` / `cmpAny` | Implemented stable `ordered(axis, Comparator<Series<Any?>>)`. Caller supplies comparison semantics; numeric keys are not stringified or coerced through Double. |
| `Cursor.kt`: `mirror` | Implemented lazy column reversal with correct `size - 1 - index`, preserving value/meta pairs. |
| `Resample.kt`: `resample(indexcol)` | Implemented `resample(indexColumn, domain: Series<Any?>)`. Appends each absent domain value once with null non-key cells; existing rows and duplicates are retained. Domain generation is explicit, replacing the JVM-only implicit LocalDate range. Requires an exemplar row. |
| `Cursor.kt`: unary minus, typed `/ Class`, flattened `% Class` | Existing `Series2` unary minus and `RowVec.values` expose lazy values. Compose `α` and current Series flattening at the intended boundary. JVM Class-based casts were not copied into commonMain. |
| `macros/Join.kt`: widening; vector `combine` | Existing `join(left,right)` widens, `combine(top,bottom)` concatenates. Existing two-input semantics retained; positional widening truncates to the shorter input. |
| `macros/Operators.kt`: cell `α`, `∑` | Current Cursor `α` is row projection. Use row/cell Series projection explicitly; fused `groupBy(axis,reducer)` handles the historical pivot/group/sum workflow. No ambiguous cellwise `α` overload added. |
| `io/Cursor.kt`: `scalars`, `colIdx`, `width`, network coordinates/sizes | Current `meta`, `columnNames`, `width`, `ColumnMeta.type.networkSize`; byte layout belongs to existing ISAM schema/codec code. |
| `io/RowVecExtensions.kt`: nullable typed access, context lookup | Current `RowVec.values`, `getValue`, `stringValue`, `longValue`, `doubleValue`, `intValue`, typed `ColK` projections. These have their own conversion/default behavior; no claim of identical nullable coercions. |
| `SimpleCursor`, `cursorOf`, CSV/FWF/ISAM/mmap writers/readers | Existing `SimpleCursor`, Confix CSV path, and userspace ISAM paths. No second JVM file layer imported. Their existing tests remain authoritative; this patch does not claim legacy format parity. |
| `Categories.kt`: `categories`, `asBitSet` | Not assimilated here. Historical dummy-column selection and Java BitSet materialization need an explicit current API/storage decision; printed category demos were not treated as verified coverage. |
| `Cursor.kt`: `normalizeFloatColumn`, `normalizeDoubleColumn`, `inner_normalize`; `vec/ml/FeatureRange` | Not assimilated here. Historical constant/empty-column behavior, Float output type, NaN handling and normalized range metadata are not a sound contract to copy implicitly. |
| `Clusters.kt`: `mapOnColumns`, `mapOnColumnsMd4`, `arrayMapOnColumns`, `trieOnColumns`, `bloomAccess` | Not assimilated here. Hash/trie/Bloom adapters and duplicate-key collision policies are distinct from grouping; no new index implementation or MD4 dependency introduced. |
| Calendar feature ranges, image raster, display helpers, experimental Gilbert traversal | Outside this Cursor transformation recovery. No old JVM calendar/UI dependencies imported. |

This is an explicit recovery of the listed executable path, not a claim that every feature in the old repository has been replaced.

## Behavioral recovery

Historical `CursorKtTest` combines real checks (fixed-width values, ordered keys, rejoined columns) with pivot/group methods that only print. `CategoriesKtTest`, `ByColNameKtTest`, `SimpleCursorTest`, `HeapCursorTest` and much of `DayJobTest` likewise mostly display results. `NegateColumnTest` asserts row counts, not projected values. These observations informed newly authored assertions; printing a table is not evidence that its values are right.

| Historical evidence | Current verification |
|---|---|
| Projection, exclusion and rejoin examples | `CursorColumnarTest.projectionExclusionAndMirrorPreservePairs`; existing Cursor indexing/combinator tests |
| Pivot quotient/remainder arithmetic and printed pivot/group/reduce chains | Full sparse cross-tabulation with deterministic expected values, composite keys, repeated fanout, null cells and schema assertions |
| Group and order examples | Generic and split rows, null keys, equal keys, deliberate hash collisions, first-seen groups, stable equal-key ordering, noncommutative reducer order |
| LocalDate gap-fill examples | Explicit deterministic domain; absent keys appear exactly once, original duplicate rows and metadata retained |
| Vector conversion / `α` examples in `VectorLikeKtTest` | `IterableProjectionTest`: List backing stays visible, Series stays lazy, unindexed Iterable materializes once, transform remains lazy |
| `NinetyDegreeTest`, `io/ISAMCursorKtTest`, Kotlin `DatabinanceKlineIsamTest` | Meaningful binary/metadata roundtrips inventoried; current `JvmIsamOperationsTest` and `IsamMetaFileReaderTest` own storage validation. Legacy market fixtures and date/instant wire codecs were not ported in this change. |
| Commented `vec/Vect0rTakeTest` and commented Java-directory KLine test | Not counted as executable historical coverage |

The prior `groupBy` cast ordinary rows to `ReifiedSplitSeries2`, then cast Series hash keys to `List<List<Any?>>`; Series equality did not group equal cell values. The replacement snapshots keys and row ordinals only, leaving non-key values as lazy projections. Grouped Series-valued columns now carry `IoArray` metadata with the original column as child schema. Reduced scalar cells retain the first member's metadata; callers must choose reducers consistent with that type. Source data must remain stable while a grouping/ordering/pivot key index is in use; values are not a transaction snapshot.

The focused build also exposed an existing `Iterable.α` compile defect: an Iterable was indexed as a List and paired with a nullable size. The fix preserves Series or List-backed indexing and materializes only an unindexed Iterable.

## Provenance and licensing

Columnar's [LICENSE at the inspected revision](https://github.com/jnorthrup/columnar/blob/0534034f1cce5e3c633e4217354559aa15104447/LICENSE) starts with `copyright 2019-2021 James Northrup` and expressly restricts its grant to GPLv2, excluding later revisions. Its `LICENSE.md` also contains GPLv2. TrikeShed's root LICENSE is AGPLv3. No Columnar implementation, fixture, test text or license grant has been copied or relabeled here. The added code and tests are newly authored against the current algebra from the behavioral inventory above; the original files remain linked for provenance. This document does not assert an additional licensing permission or relicense the historical repository.

## Reproduction

Run the repository's focused Cursor verification and synthetic benchmark under `bench/columnar` (see its README). They compile current source files and generate their own data; neither reads `/tmp/trikeshed-columnar-b163`. This source slice is separate from the root multiplatform build, so a passing run does not establish whole-repository or native/JS/Wasm build health. Initial root build blockers were repaired separately; JVM publication and selected integration tests now pass. The focused run's exact versions/results are reported with the benchmark evidence. The persistent ISAM trace sleeve additionally exercises current metadata and data files through the shared facade; see `bench/columnar/io/README.md` and its Linux evidence.
