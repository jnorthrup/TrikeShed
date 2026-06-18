# MutableSeries Class Hierarchy

> TrikeShed mutable collections type algebra • kotlin multiplatform

## Type Alias Foundation

```mermaid
%%{init: {"theme": "dark", "themeVariables": {"primaryColor": "#a78bfa", "edgeLabelBackground": "#020617", "tertiaryColor": "#1e293b"}}}%%
classDiagram
    class Join~A, B~ {
        <<interface>>
    }
    class MetaSeries~I, T~ {
        <<typealias>>
        = Join~I, (I) -> T~
    }
    class Series~T~ {
        <<typealias>>
        = MetaSeries~Int, T~
    }
    class Series2~A, B~ {
        <<typealias>>
        = Series~Join~A, B~~
    }

    Join <|-- MetaSeries : typealias
    MetaSeries <|-- Series : typealias
    Series <|-- Series2 : typealias
```

## Interface + Default Implementation

```mermaid
%%{init: {"theme": "dark", "themeVariables": {"primaryColor": "#fb7185", "edgeLabelBackground": "#020617", "tertiaryColor": "#1e293b"}}}%%
classDiagram
    class MutableSeries~T~ {
        <<interface>>
        + append(item: T): Unit
        + insert(index: Int, item: T): Unit
        + set(index: Int, item: T): Unit
        + removeAt(index: Int): T
        + remove(item: T): Boolean
        + clear(): Unit
        + freeze(): Series~T~
        + cowSnapshot(): MutableSeries~T~
    }

    class COWArrayBackend~T~ {
        <<class>>
        - arr: Array~Any?~
        - frozen: Boolean
        - ver: Long
    }

    class FrozenArray~T~ {
        <<class>>
        + arr: Array~Any?~
        + thaw(): MutableSeries~T~
    }

    Series <|-- MutableSeries : extends
    MutableSeries <|-- COWArrayBackend : implements
    MutableSeries <|.. FrozenArray : returns
```

## Implementations with MutableSeries Base

```mermaid
%%{init: {"theme": "dark", "themeVariables": {"primaryColor": "#34d399", "edgeLabelBackground": "#020617", "tertiaryColor": "#1e293b"}}}%%
classDiagram
    MutableSeries <|-- ChunkedMutableSeries
    MutableSeries <|-- DequeSeries
    MutableSeries <|-- GuardSeries
    MutableSeries <|-- SortedSeries
    MutableSeries <|-- PointcutMutableSeries
    MutableSeries <|-- RecursiveMutableSeries
    MutableSeries <|-- COWArrayBackend

    class ChunkedMutableSeries~T~ {
        <<class>>
        + chunkSize: Int = 4096
    }

    class DequeSeries~T~ {
        <<class>>
        + front: Series~T~
        + back: Series~T~
    }

    class GuardSeries~T~ {
        <<class>>
        + guard: (T) -> Boolean
        + inner: MutableSeries~T~
    }

    class SortedSeries~T~ {
        <<class>>
        + comparator: (T, T) -> Int
    }

    class PointcutMutableSeries~T~ {
        <<class>>
        + delegate: MutableSeries~T~
        + actionSink: (MutationAction~T~) -> Unit
    }

    class RecursiveMutableSeries~T~ {
        <<class>>
        + data: Series~T~
    }
```

## Standalone Implementations (No MutableSeries Base)

```mermaid
%%{init: {"theme": "dark", "themeVariables": {"primaryColor": "#fbbf24", "edgeLabelBackground": "#020617", "tertiaryColor": "#1e293b"}}}%%
classDiagram
    class JournalSeries~T~ {
        <<class>>
        + delta: MutableSeries~Delta~T~~
    }

    class MergedSeries~T~ {
        <<class>>
        + input: MutableSeries~T~
        + sorted: SortedSeries~T~
        + mergeThreshold: Int
        + drain(): Unit
    }

    class ReduxMutableSeries~A, S~ {
        <<class>>
        + eventJournal: MutableSeries~A~
        + reducer: Reducer~A, S~
        + state: S
    }

    class RingSeries~T~ {
        <<class>>
        + capacity: Int
        + head: Int
        + onEvict: (T) -> Unit
    }
```

## Full Hierarchy Graph

```mermaid
%%{init: {"theme": "dark", "themeVariables": {"primaryColor": "#22d3ee", "edgeLabelBackground": "#020617", "tertiaryColor": "#1e293b"}}}%%
graph TD
    subgraph Foundation
        J["Join~A, B~<br/>lib/Join.kt:1"] --> MS["MetaSeries~I, T~<br/>= Join~I, (I)->T~"]
        MS --> S["Series~T~<br/>= MetaSeries~Int, T~"]
    end

    subgraph Interface
        S --> MT["MutableSeries~T~<br/>interface<br/>mutable/MutableSeries.kt:17"]
    end

    subgraph BaseImpls
        MT --> COW["COWArrayBackend~T~<br/>class<br/>MutableSeries.kt:62"]
        MT --> CHK["ChunkedMutableSeries<br/>class"]
        MT --> DEQ["DequeSeries<br/>class"]
        MT --> GRD["GuardSeries<br/>class"]
        MT --> SRT["SortedSeries<br/>class"]
        MT --> PCT["PointcutMutableSeries<br/>class"]
        MT --> REC["RecursiveMutableSeries<br/>class"]
    end

    subgraph Standalone
        JRN["JournalSeries<br/>class"]
        MRG["MergedSeries<br/>class<br/>+drain()"]
        RDX["ReduxMutableSeries<br/>class"]
        RNG["RingSeries<br/>class"]
    end

    subgraph Snapshot
        COW --> FRZ["FrozenArray~T~<br/>class<br/>MutableSeries.kt:177"]
    end

    style J fill:#4c1d95,stroke:#a78bfa
    style MS fill:#4c1d95,stroke:#a78bfa
    style S fill:#4c1d95,stroke:#a78bfa
    style MT fill:#881337,stroke:#fb7185
    style COW fill:#064e3b,stroke:#34d399
    style CHK fill:#064e3b,stroke:#34d399
    style DEQ fill:#064e3b,stroke:#34d399
    style GRD fill:#064e3b,stroke:#34d399
    style SRT fill:#064e3b,stroke:#34d399
    style PCT fill:#064e3b,stroke:#34d399
    style REC fill:#064e3b,stroke:#34d399
    style JRN fill:#78350f,stroke:#fbbf24
    style MRG fill:#78350f,stroke:#fbbf24
    style RDX fill:#78350f,stroke:#fbbf24
    style RNG fill:#78350f,stroke:#fbbf24
    style FRZ fill:#4c1d95,stroke:#a78bfa
```

## Summary

| Category | Count | Files |
|----------|-------|-------|
| Foundation types | 3 | lib/Join.kt |
| Interface | 1 | mutable/MutableSeries.kt:17 |
| Extends MutableSeries | 6 | Chunked, Deque, Guard, Sorted, Pointcut, Recursive |
| Standalone | 4 | Journal, Merged, Redux, Ring |
| Snapshots | 1 | FrozenArray |

## Source References

- `lib/Join.kt:1` — `interface Join<A, B>`
- `lib/Join.kt:7` — `typealias Series<T> = MetaSeries<Int, T>`
- `mutable/MutableSeries.kt:17` — `interface MutableSeries<T> : Series<T>`
- `mutable/MutableSeries.kt:62` — `class COWArrayBackend<T>`
- `mutable/MutableSeries.kt:177` — `class FrozenArray<T>`