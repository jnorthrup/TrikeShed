# TrikeShed

<p align="left">
  <a href="https://jnorthrup.github.io/TrikeShed/"><img alt="GitHub Pages final production" src="https://img.shields.io/badge/GitHub%20Pages-Final%20Production-0b0f14?style=for-the-badge&logo=github&logoColor=white"></a>
  <a href="https://jnorthrup.github.io/TrikeShed/"><img alt="Forge production signals" src="https://img.shields.io/badge/Forge-Production%20Signals-7aa2f7?style=for-the-badge"></a>
</p>

> **Explore the live site:** [jnorthrup.github.io/TrikeShed](https://jnorthrup.github.io/TrikeShed/)  
> Source-grounded GitHub Pages home for Forge production signals: published introspection assets, PRELOAD contract excerpts, working-tree signals, and direct links into runtime / series / triple proofs.

**A compositional, blackboard-based fabric for autonomous LLM workflows with real-time human–agent collaboration.**

TrikeShed is a systems substrate for building reliable, observable, multi-step workflows that combine LLMs, coding agents, and human collaborators on shared state.

Workflows are expressed as **algebraic compositions** rather than ad-hoc chains or simple DAGs. State is managed through a **blackboard architecture** with cursor-based real-time synchronization, immutable snapshots, and strong artifact provenance. The design draws from classical AI blackboard systems, modern effectful composition, and collaborative knowledge tools — the spiritual successor to “GNU Autotools × Notion” for agentic work.

## Forge: The Visual Front-End

The included Forge interface renders the complete working surface in real time:

- **Workspace metrics** (files, snapshots, prompts, workflows, executions, cursor rows, collab events, DB rows)
- **Preload algebra chain** visualization: `Join<A,B>` → `Series<T>` → `Cursor` → `ConfixDoc` → `Blackboard` → `CCEK`
- Live multi-user collaboration (cursor presence, simultaneous edits)
- Execution provenance with stable IDs and full trace
- Cascade outputs with quantitative scores (α / β / γ)
- Telemetry analysis demo pipeline
- Notion-backed model layer
- Explicit extensibility surface (“Forge Hinges”)

The interface is deliberately instrumented so every layer — from algebraic composition down to individual agent steps — remains visible and actionable.

## Core Architectural Concepts

### Compositional Algebra
Workflows are constructed from a small, typed set of operators that compose cleanly:

```kotlin
// Kernel algebra from PRELOAD.md
interface Join<A, B> { val a: A; val b: B }
infix fun <A, B> A.j(b: B): Join<A, B> = Join(this, b)

typealias Twin<T> = Join<T, T>
typealias Series<T> = Join<Int, (Int) -> T>

// Lazy projection over a Series
infix fun <X, C, V : Series<X>> V.α(xform: (X) -> C): Series<C>
```

**Key operators:**
- `Join<A,B>` — binary composition (the base pattern)
- `j` — infix constructor: `a j b` creates `Join(a, b)`
- `Twin<T>` — same-typed pair: `Join<T, T>`
- `Series<T>` — size + index function: `Join<Int, (Int) -> T>`
- `α` — lazy map/projection over a Series

See [`PRELOAD.md`](PRELOAD.md) for full kernel algebra documentation.

### Cursor — Columnar Data Abstraction

```kotlin
typealias RowVec = Series2<Any, () -> RecordMeta>
typealias Cursor = Series<RowVec>
```

Cursors are the dataframe-shaped specialization of the kernel algebra:
- `cursor[i]` — select row by index
- `cursor[i0 until i1]` — range view
- `cursor["name","age"]` — project by column name
- `join(cursor1, cursor2)` — widen along columns

### Blackboard Architecture

TrikeShed implements a classical AI blackboard pattern with modern enhancements:

- **Shared state surface** — all agents operate on a common working memory
- **Cursor-based synchronization** — real-time multi-user collaboration
- **Immutable snapshots** — full provenance and rollback
- **Algebraic composition** — workflows as compositions, not DAGs

### Reactor — io_uring-Based Async Engine

The reactor models RelaxFactory's single-threaded selector + attachment-chain pattern, translated to coroutines and io_uring:

```kotlin
class HtxTransport(channel: Channel) : SelectableChannelOps {
    suspend fun execute(request: HtxClientRequest): HtxClientMessage {
        val runner = ChannelRunner(channel, coroutineScope)
        // connect → write → read → deliver (coroutine suspension)
    }
}
```

- Multiple concurrent Jobs share one uring ring
- Each Job runs its own FSM via `ChannelRunner.runOp()`
- CQE completions fan out via `userData` token mapping

See [`src/README.md`](src/README.md) for full reactor documentation.

### HTX — Version-Agnostic HTTP Tokenizer

HTX is the common tokenizer for HTTP/1.x, HTTP/2, and HTTP/3, following HAProxy's internal `htx_blk` format:

```kotlin
// Block sequence identical across all HTTP versions
HtxMessage: [ReqSl · Hdr · Hdr · EOH · Data · EOT · EOM]
```

The block sequence is identical whether bytes arrived via HTTP/1.1 text, HTTP/2 frames, or HTTP/3 QUIC.

### Platform Support

| Target | IO Backend | Status |
|--------|-----------|--------|
| `jvmMain` | JDK NIO | ✅ |
| `linuxMain` | io_uring (cinterop) | ✅ |
| `posixMain` | POSIX fallback | ✅ |
| `macosMain` | POSIX fallback | ✅ |
| `jsMain` | POSIX fallback | ✅ |
| `wasmJsMain` | Unsupported | ⚠️ |

---

## Project Structure

```
TrikeShed/
├── src/                    # Core substrate (commonMain + platform targets)
│   ├── commonMain/kotlin/   # Shared kernel + HTX + choreography
│   ├── lib/              # Kernel algebra (Join, Series, Twin, α, j)
│   ├── context/          # AsyncContextElement, lifecycle
│   └── ...
├── libs/                  # Functional modules
│   ├── couch/           # HTX document store + WAL
│   ├── ipfs/            # DHT + content routing
│   ├── forge/          # Visual collaboration UI
│   ├── activejs/       # JS interop
│   └── ...
├── docs/                 # Design documents + demos
├── io_uring_interop/     # C headers for io_uring
└── build.gradle.kts     # Gradle root config
```

---

## Quick Start

```bash
# Build everything
./gradlew build

# Run the Forge demo
./gradlew :libs:forge:jsRun

# Run IPFS console demo
./gradlew :libs:ipfs:run

# Run Couch WAL demo
./gradlew :libs:couch:runWal
```

---

## Key Design Documents

- [`PRELOAD.md`](PRELOAD.md) — Kernel algebra: `Join`, `Series<T>`, `Twin`, `α`, `j`
- [`src/README.md`](src/README.md) — Core substrate: reactor, HTX, transport, choreography
- [`BUILD_GUIDE.md`](BUILD_GUIDE.md) — Build instructions and tasks
- [`BOUNDARY_CONTRACT_SUMMARY.md`](BOUNDARY_CONTRACT_SUMMARY.md) — Module contracts
- [`IPFS_INTEGRATION_REFINED.md`](IPFS_INTEGRATION_REFINED.md) — IPFS integration details
- [`libs/couch/WAL_DESIGN.md`](libs/couch/WAL_DESIGN.md) — Write-Ahead Log design
- [`libs/couch/MINIDUCK_DESIGN.md`](libs/couch/MINIDUCK_DESIGN.md) — Query engine design

---

## Modules

### Core Substrate (`src/`)

| Module | Purpose |
|--------|---------|
| `lib/` | Kernel algebra: Join, Series, Twin, α, Cursor |
| `context/` | AsyncContextElement, lifecycle, fanout |
| `charstr/` | Text handling (CharStr, CharStrCached) |
| `collections/` | Series, HashSeriesSet, NavigableSet, associative |
| `dht/` | Distributed hash table primitives |
| `indicator/` | Feature extraction, DoubleSeries |
| `isam/` | Indexed sequential access method |
| `manifold/` | Concept manifolds |
| `mlir/` | MLIR core |
| `num/` | BigInt, numeric operations |

### Library Modules (`libs/`)

| Module | Purpose |
|--------|---------|
| `couch/` | HTX document store, WAL, query engine |
| `ipfs/` | DHT, content routing, Kadmelia |
| `forge/` | Visual collaboration UI |
| `activejs/` | JS platform interop |
| `tspy/` | Cursor/pointcut tooling |
| `classfile/` | JVM classfile parsing |
| `cursor/` | Cursor abstractions |

---

## Technology Stack

- **Language**: Kotlin Multiplatform (common, jvm, linux, posix, macos, js, wasmJs)
- **Build**: Gradle 8.x with version catalogs
- **IO**: io_uring (Linux), POSIX fallback (macOS/js/wasm)
- **Async**: Kotlin Coroutines + Structured Concurrency
- **Serialization**: CBOR, JSON, HTX wire format

---

## License

See [`LICENSE`](LICENSE) file for details.
