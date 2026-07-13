# Cursor: Before and After RowVec Family Model

## BEFORE — Cursor as flat rectangular rows

```mermaid
classDiagram
    direction TB

    class Series~T~ {
        <<typealias>>
        Int size
        (Int) -> T get
    }

    class Join~A_B~ {
        <<typealias>>
        A first
        B second
    }

    class RowVec {
        <<typealias Series~Join~Int_Any~~>>
        Int columns
        (Int) -> Any? col
    }

    class Cursor {
        <<typealias Series~RowVec~>>
        Int rows
        (Int) -> RowVec row
    }

    Series~T~ <|-- RowVec : specializes
    Series~T~ <|-- Cursor : specializes
    RowVec ..> Join~A_B~ : cell is Join(Int, Any?)

    note for Cursor "Flat. Rectangular.\nNo nesting. No families.\nEvery row same shape."
    note for RowVec "Any? columns only.\nNo lazy children.\nNo block boundary."
```

---

## AFTER — Cursor over RowVec family hierarchy (MiniDuck)

```mermaid
classDiagram
    direction TB

    class Cursor {
        <<typealias Series~RowVec~>>
        rows: Int
        row(i): RowVec
    }

    class RowVec {
        <<sealed family root>>
        size: Int
        col(i): Any?
        child: Series~RowVec~?
    }

    class BlockRowVec {
        state: MUTABLE | SEALED
        append(row): Unit
        seal(): BlockRowVec
        children: Series~RowVec~
    }

    class DocRowVec {
        scalar top-level fields
        child: nested objects/arrays
    }

    class ViewRowVec {
        id / key / value / doc
        child: deferred doc expansion
    }

    class JsonRowVec {
        parse-tree over blob
        child: keys / elements / leaves
    }

    class YamlRowVec {
        parse-tree over blob
        child: mappings / seqs / leaves
    }

    class BlobRowVec {
        zero-length shell
        child: metadata / MIME / projections
    }

    class BlackboardOverlay {
        role: OverlayRole
        provenance: Provenance
        decorates: RowVec
    }

    class OverlayRole {
        <<enum>>
        OBSERVATION
        DERIVED
        AGGREGATE
        HYPOTHESIS
        GROUND_TRUTH
        PROVENANCE
    }

    class Shape {
        <<typealias Series~Int~>>
    }

    class Tensor~T~ {
        <<typealias Join~Shape_(Shape)->T~>>
        lowering only
    }

    Cursor --> RowVec : Series of
    RowVec <|-- BlockRowVec : specializes
    RowVec <|-- DocRowVec : specializes
    RowVec <|-- ViewRowVec : specializes
    RowVec <|-- JsonRowVec : specializes
    RowVec <|-- YamlRowVec : specializes
    RowVec <|-- BlobRowVec : specializes

    BlockRowVec --> RowVec : children (sealed block contains)

    BlackboardOverlay --> RowVec : decorates parent or child
    BlackboardOverlay --> OverlayRole : role

    Tensor~T~ ..> Shape : uses
    BlockRowVec ..> Tensor~T~ : optional lowering path

    note for BlockRowVec "mutable -> sealed\none writer / sealed\nsealing = sync boundary"
    note for BlobRowVec "zero-length parent is valid\nmeaning deferred into children"
    note for Tensor~T~ "NOT semantic center\noptional exec backend only"
```

---

## Key Delta

| Aspect | Before | After |
|---|---|---|
| Cursor typedef | `Series<RowVec>` | `Series<RowVec>` (unchanged) |
| RowVec shape | flat `Any?` column bag | sealed family root with lazy `.child` |
| Nesting | none | arbitrary depth via `.child: Series<RowVec>?` |
| Block boundary | none | `BlockRowVec` mutable → sealed (DuckDB-style) |
| Locking | none | one writer per mutable block; sealed = read-many |
| Blob / doc / JSON | not modeled | `BlobRowVec`, `DocRowVec`, `JsonRowVec`, `YamlRowVec` |
| Blackboard | not connected | `BlackboardOverlay` decorates any family row |
| Zero-length rows | meaningless | first-class (deferred child meaning) |
| Tensor | not present | optional lowering only; `Shape = Series<Int>` |
| View expansion | flat result | `ViewRowVec` with deferred `doc` child |
