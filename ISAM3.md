# ColumnarIsam `isam: 3` TDD Plan

## Summary
Drive `isam: 3` from tests first. One YAML layout describes partitions, files, IoMeta groups, sparse coordinates, views, and store flags. `ColumnarIsam` must use that layout as the single metadata source.

## YAML Fixtures

Packed cluster:

```yaml
isam: 3
klines:
  klines.time:
    IoInstant: [Open_time, Close_time]
  klines.price:
    IoDouble: [Open, High, Low, Close]
views:
  klines: [Open_time, Close_time, Open, High, Low, Close]
```

Sparse coordinates:

```yaml
isam: 3
klines: [coords]
  klines.row: [0]
    IoInstant:
      Open_time: 0
      Close_time: 12
    IoDouble:
      Open: 24
      Close: 48
views:
  price: [Open, Close]
```

Variable width:

```yaml
isam: 3
ticker:
  ticker.data:
    IoString: [16, Symbol]
    IoDouble: [Close]
```

## Red Tests, In Order
Create one common test file first: `src/commonTest/kotlin/borg/trikeshed/isam/Isam3ColumnarTest.kt`.

1. `parsesPackedIoMetaColumns`
   - reads packed YAML
   - gets partition `klines`
   - gets files `klines.time`, `klines.price`
   - gets columns once: `Open_time`, `Close_time`, `Open`, `High`, `Low`, `Close`

2. `infersFixedWidthsFromIoMemento`
   - `IoInstant` width = 12
   - `IoDouble` width = 8
   - packed offsets are prefix sums inside each file

3. `keepsIoMetaFirstColumnBasis`
   - logical `ColumnMeta` derives from `IoMeta -> column names`
   - no separate repeated `cols` section required

4. `parsesSparseCoordinates`
   - explicit coordinates become `RecordMeta.begin`
   - width still comes from `IOMemento`
   - sparse gaps are allowed

5. `rejectsUndersizedExplicitSpan`
   - `IoDouble` with available span `< 8` throws
   - exception names column, type, required width, actual width

6. `rejectsVariableWidthWithoutLen`
   - `IoString: [Symbol]` throws
   - `IoString: [16, Symbol]` passes

7. `parsesViewsAsCursorSurfaces`
   - `views.price` resolves to `[Open, Close]`
   - Cursor order follows view order, not physical file order

8. `supportsRedundantColumnsAcrossFiles`
   - same column appears in two files
   - logical metadata has one column identity
   - selected store decides physical source

9. `parsesStoreFlagsWithoutSchemaBranch`
   - file tuple flags like `[index, readonly]` are retained
   - flags do not alter logical Cursor metadata

10. `columnarWriteCreatesOneLayout`
   - `ColumnarIsam.write` creates one `isam: 3` YAML layout
   - does not create per-file `.meta` sidecars

11. `columnarRoundTripPackedClusters`
   - write tiny Cursor
   - reopen through layout
   - row/cell values match
   - physical files match declared clusters

12. `selectedViewCanJoinMultipleFiles`
   - view spans columns from time and price files
   - resulting Cursor row is a joined RowVec over both sources

## Implementation Targets
- Add `Isam3Layout` in common code:
  - parse YAML via root Confix/YAML helpers
  - expose partitions, files, IoMeta groups, views, flags
  - derive `ColumnMeta` and selected-store `RecordMeta`
- Update `ColumnarIsam`:
  - consume/write `isam: 3`
  - infer packed geometry from `IOMemento`
  - join files for selected views
- Keep old text metadata as a compatibility adapter only; it is not the new model.

## Acceptance
`./gradlew :compileCommonMainKotlinMetadata --no-daemon` and the new common test file pass. Existing unrelated dirty `libs/miniduck` failures are not part of this slice.
