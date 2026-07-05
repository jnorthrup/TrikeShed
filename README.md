++(Bike)Shed library

many things

---

## Repository split (2026-07-05)

This repo (`jnorthrup/TrikeShed`) is **self-contained at root `src/`** and has no `libs/`
directory. All former `libs/` subprojects were extracted verbatim into a sibling repo:

| Repo | Holds |
|---|---|
| `jnorthrup/TrikeShed` (this one) | Root KMPP `src/` engine — Forge, CCEK, dag/rete, lcnc reductions, blackboard, kanban, cursors, JSON scanner, ISAM/FlatFile, IO |
| `jnorthrup/trikeshed-libs` | Every former `libs/<name>` subproject as a standalone Gradle module (acpmcp, asclepius, ccek-core, cmc, couch, forge, htx-client, ipfs, krak, lcnc, lsm, og1, openapi, patl, polyglot, quic, tls, torrent, user-signals, …) |

### Re-attaching the libs tree

`settings.gradle.kts` auto-includes any `libs/<name>/` that has a `build.gradle.kts`, but only
when `libs/` exists locally. The `runOpenApiDemo` task is similarly conditional on
`libs/openapi/`. Two supported mounts:

1. **Submodule**
   ```
   git submodule add https://github.com/jnorthrup/trikeshed-libs libs
   ```
2. **Sibling checkout + composite build**
   ```
   git clone https://github.com/jnorthrup/trikeshed-libs ../trikeshed-libs
   ./gradlew --include-build ../trikeshed-libs :jvmTest
   ```

With neither in place, TrikeShed builds and tests standalone against only `src/`. The root
source set has zero `libs/` imports.

> Note: the prose below describes `libs/forge` / `libs/forge-ui`, which now live in
> `trikeshed-libs`. The canonical board types (`KanbanBoard`, `KanbanCard`, `KanbanColumn`,
> `CardPriority`) remain in **root commonMain** at `borg.trikeshed.kanban`.

---

## Forge & Forge-UI

`libs/forge` and `libs/forge-ui` form the agentic workflow layer of TrikeShed: composable pipelines, a live Kanban board, and a headless recording/replay system. They now live in
`jnorthrup/trikeshed-libs`. The canonical board types (`KanbanBoard`, `KanbanCard`,
`KanbanColumn`, `CardPriority`) remain in root commonMain `borg.trikeshed.kanban` — no
forge dependency required for consumers that only need the board model.

### forge — workflow engine (`libs/forge`)

Package root: `borg.trikeshed.forge`

| Subsystem | Key types | Purpose |
|---|---|---|
| **Workspace** | `ForgeWorkspace`, `ForgeFile`, `ForgePrompt`, `ForgeArtifact` | CRUD over a project workspace; import/export bundles |
| **Workflow execution** | `ForgeStepRunner`, `ForgeAgentRunner` | Drive `LlmCall`, `CodeExecution`, `AgentInvocation` (CODEX/CLAUDE), `FileTransform`, `Conditional`, `Parallel`, `CascadeExecution` |
| **Cascade pipelines** | `MapStage`, `ReduceStage`, `FilterStage`, `JoinStage` | CouchDB-style map/reduce/rereduce over `FileSource`, `WorkflowOutput`, `CursorSource` |
| **Patch-bay routing** | `PatchBayModule`, `PatchCable`, `PatchCableShapeDimension` | 18-dimensional typed port routing (`DATA`/`CV`/`GATE`/`AUDIO`/`VIDEO`); hot-swap cables |
| **Model gateway** | `ModelKeyGateway`, `MemoryCAS`, `KeyEntry` | Key lease/backoff/bench lifecycle across providers |
| **Kanban overlay** | `KanbanForgeExtensions` | `toCascadeGraph()`, `toMermaid()`, `toDot()`, `toPatchBayModules()` on root board types |
| **Cursor Notion** | `CursorDrivenNotion`, `NotionKanbanBridge`, `TaxonomyCreator` | Sync taxonomy nodes to/from a Kanban board |
| **Agent swarm** | `SwarmRoot`, `SwarmWorker`, `SwarmVerifier`, `SwarmSynthesizer` | Multi-agent coordination with `renderMermaid()`/`renderDot()` visualisation |
| **Operational dashboard** | `OperationalEntry`, `DashboardView` | GAUGE/LINE/BAR/TABLE/HEATMAP/STATUS_GRID views; pre-named pools |

The canonical board types (`KanbanBoard`, `KanbanCard`, `KanbanColumn`, `CardPriority`) live in **root commonMain** at `borg.trikeshed.kanban` — no forge dependency required for consumers that only need the board model.  `libs/forge/KanbanTypes.kt` is pure typealiases into that package.

---

### forge-ui — Compose Desktop Kanban + live server (`libs/forge-ui`)

Package root: `borg.trikeshed.forge.ui`  
Run: `./gradlew :libs:forge-ui:run`

| Component | File | Purpose |
|---|---|---|
| **KanbanBoardScreen** | `KanbanBoardScreen.kt` | Fluid drag-and-drop Compose Desktop board; WIP limit badges; narration overlay; recording toolbar (▶ / ⬛ / frame counter / "Show video") |
| **ForgeBoardFSM** | `src/commonMain/.../kanban/ForgeBoardFSM.kt` | Board lifecycle FSM; `StateFlow<ForgeBoardState>`; drag events (`DragStarted`, `DragOver`, `DragDropped`); singleton `object` |
| **ReactorServer** | `ReactorServer.kt` | Embedded HTTP/SSE server; `/events` stream; `/taxonomy?topic=` endpoint injects `KanbanEvent.TaxonomyNodeCreated`; restartable; `boundPort: AtomicInteger` |
| **WalkthroughTypes** | `WalkthroughTypes.kt` | Declarative script DSL — `WalkthroughScript`, `WalkthroughStep`, `WalkthroughAction` sealed class (14 subtypes) |
| **WalkthroughPlayer** | `WalkthroughPlayer.kt` | Coroutine playback driving `ForgeBoardFSM`; wraps `ForgeRecorder`; exports `~/Movies/forge-<id>.mp4` |
| **ScreenRecorder** | `ScreenRecorder.kt` | AWT `Robot.createScreenCapture()` → PNG frames → ffmpeg pipe → H.264 MP4; narration `drawtext` burnt into stream |
| **OffscreenRenderer** | `OffscreenRenderer.kt` | Java2D `BufferedImage` board renderer — no screen, no Compose; RGB24 frame pipe to ffmpeg for headless use |
| **HeadlessWalkthroughRunner** | `OffscreenRenderer.kt` | Runs any `WalkthroughScript` headlessly; prints full board-state trace to stdout per step |

Run all headless usecase videos (no display needed):

```
./gradlew :libs:forge-ui:runHeadless
# outputs:  ~/Movies/forge-walkthroughs/
#   1_teach_pendant_robot.mp4   — robot arm weld pipeline teach-pendant
#   2_ai_kanban_benefits.mp4    — AI agent auto-grooms and advances board
#   3_recording_replay_demo.mp4 — record operator actions, replay headlessly
```

---

### User signals / usecase principals

Three principals drive the board:

**1. Human operator (teach-pendant model)**  
The operator drags cards through the board while the recording system captures every action as a `WalkthroughScript`.  The script is deterministic and replayable: any future CI run or demo can reproduce the exact sequence without a human present.

**2. AI agent (autonomous grooming)**  
An agent process emits `ForgeBoardEvent`s directly into `ForgeBoardFSM` — creating cards from a backlog analysis, respecting WIP limits, and closing tasks when done.  The same recording pipeline captures this; the resulting video is a verifiable audit trail of what the agent did and in what order.

**3. External system (SSE / taxonomy injection)**  
`ReactorServer` exposes `/taxonomy?topic=` so external tools (CI pipelines, LLM orchestrators, Notion webhooks) can inject `TaxonomyNodeCreated` events over HTTP.  These surface as board cards in real time and appear in the SSE stream at `/events`.

All three principals produce the same observable: a sequence of `ForgeBoardEvent`s that the FSM reduces to a `KanbanBoard` snapshot, the renderer turns into frames, and ffmpeg encodes to a shareable MP4.

---

### Teach-pendant robot walkthrough (recorded demo)

The video below was produced headlessly by `HeadlessWalkthroughRunner` — no display attached, pure Java2D → ffmpeg pipe.  It shows a robot-arm weld pipeline (`Calibrate → Load Fixtures → Run Weld Sequence → Inspect`) flowing through Backlog → In Progress → Review → Done.

https://github.com/jnorthrup/TrikeShed/raw/master/docs/media/teach_pendant_robot.mp4

> Recorded at 1280×800 · 30 fps · H.264 · ~28 s  
> Re-render anytime: `./gradlew :libs:forge-ui:runHeadless`

---

## tldr --

this is the backbone of the json scanner and the fast-enough single-threaded Flat/ISAM <sup>[1]</sup>  database within
trappings.

```kotlin 
interface Join<A, B> {
    val a: A
    val b: B
    operator fun component1(): A = a//destructuring 1&2
    operator fun component2(): B = b
    val pair: Pair<A, B> get() = Pair(a, b); ...
}

typealias Twin<T> = Join<T, T>
typealias Series<T> = Join<Int, (Int) -> T>

val <T> Series<T>.size: Int get() = a

/** index operator for Series*/
operator fun <T> Series<T>.get(i: Int): T = b(i)
[...] dozens of mix - ins and specializations

typealias JsElement = Join<Twin<Int>, Series<Int>> //(openIdx j closeIdx) j commaIdxs
typealias JsIndex = Join<Twin<Int>, Series<Char>> //(element j src)
typealias JsContext = Join<JsElement, Series<Char>> //(element j src)
typealias JsPathElement = Either<String, Int>
typealias JsPath = Series<JsPathElement>

typealias RowVec = Series2<Any, () -> RecordMeta>
/** Cursors are a columnar abstraction composed of Series of Joined value+meta pairs (RecordMeta) */
typealias Cursor = Series<RowVec>
 ```

## if you are still reading... I've also written ideas that describe (some) goals and ideals of the library:

* [x] strongly immmutable Join aka Pair,Twin,Series aka Array,Series2, Cursors, are all typealiases of Join
    * extending the language through index and other operators happens as a side-effect of testing new expression
      economies.

    - `myseries[4,3,2,1]` will provide a mapped Series in any order specified, even dupes. similar range indexes are
      available for other types
    - `myseries<T>[(T)->Boolean]` is a shorthand filter expression
    - `"banana".toSeries() / 'n'` would split series into `s_['ba','a','a']`
    - combine(Series...), and join(Cursor...) will concatenate and widen respectively with underlying binary-search
      index remapping on y,x axis respectively where Cursors are concerned.
    - a handful of nonstandard symbols are used to hint the code for a quick read
        * _l,_a,_s,_m util objects provide e.g. `_l[1,2,3]` for a kotlin List; s_[] is a Series
            * CharSeries is a Series<Char> with ByteBuffer token manipulation methods
            * a LongSeries<T> exists to enable 64 bit indexing e.g. file-IO random access virtualization and other large
              contiguous or sparse things
            * some symbol liberties:
                - `(myseries as Series<T>)` __α__ `{it:T-> foo(it)}` is an infix, lazy .map analog of a series
                - `(myseries as Series<T>).`\`▶\` visually noticable forward-iterator accessor denoting kotlin stdlib
                  collections/functional facade for a given purpose, typically filters, maps, or folds
                - left identity anchors, respectively __\`↺\`__ e.g. "columnname".\`↺\` to functionalize a constant or
                  other value in situations where sometimes a lambda might be generative but constant can be distinctly
                  picked out from in the code

* [x] Cursor lazy and memory-resident Dataframes lending strongly typed columns, with names, splittability,
  combinability, transforms.
    - cursor meta is provided per row per cell by a lambda. often this is by e.g. `RecordMeta("name",IoString,0,64).\`
      ↺\``
    - similar to `Series[1,3,2]` and `Series2[1,3,2]`,` Cursor[1,3,2]` will return a new cursor with the columns in the
      order
      specified. `cursor[1,1,1]` likewise will project a cursor of column 1 in 3 columns at columns 0,1,2
    - `cursor ["name","name"]` will provide a cursor from the first such named column, column "name" twice as shown.
    - `cursor[-"age",-"debug"]` will provide a new cursor with column exclusions from the existing columns
    - indexes on indexes:   `cursor["name","age"]["age","name"]` will swap the columns;
        - `cursor["name","age"][-"age"]` will remove the age column
        - `cursor["name"][0][-"name"]` would effectively return a cursor with rows but no columns
    - `combine(cursor1,cursor2)` will combine the rows in order, with the caution aboutmixing row meta, it can be done
      per row and cell but if all rows have isomorphic meta then row 0 meta the first time is good enough
    - `join(cursor1,cursor2)` will combine the columns with presumably bad results for differing row lengths- though
      myShortCursor.infinite can be used here

* [x] ~~ISAM~~ FlatFile Columnar Dataframes Storage @see http://github.com/jnorthrup/columnar
    - now with a native port. the jvm rewrite of columnar is also a full rewrite, streamlined and simplified lacking
      NIO specialization.
    - the kotlin-native ~~isam~~ FlatFile is linux-posix-64bit specific mmap code.
    - the columnar project has a lot more bells and whistles and is battle hardened
    - the default construction of an ~~ISAM~~ FlatFile volume are tested to be correct in a single threaded environment
        - [x] the jvm version employs a lock-seek-reed-unlock strategy
        - [x] the native version uses [linux] `mmap` with readonly memory.
            - [x] in practice this is copmatible with macos posix until you look into liburing integration, so the uring
              attempt was made a seperate linux-only class from the IsamVolume
            - [ ] the posix code holds up well under mingw however the mmap calls are significantly different so this
              may warrant a seperate lock-seek-read-unlock strategy for windows, or someone with ambition to port the
              mmap calls

* [x] Duck-typing CSV-Cursor which includes varchar
  width sizing and narrowing numerical of types and float/integer detection on imported columns. supports
  explicit ~~ISAM~~ FlatFile transcription on initial scan. heap stores only index to first records for CSV cursors.
    - exmaples tbd


* [x] JSON indexer/reifier/path-selector written for simplicity and speed. This is not a serdes library.
    * ~300 lines at time of writing this. no external deps. no reflection.
    * `JsonParser.index(Series<Char>)` will return a `JsElement` with CharSeries `segments` of the top level element.  
      each segment is the complete json object, array, or value including the open/close brackets to recreate
      the json and act as discriminators for the type of the segment.
        * optional depth list param will record how deep each segment is during the single-level scan of the input
        * optional field cutoff param will parse only the first n fields of the top level element. A very specific
          tabular use case drives this and the unparsed elements all come back in one abandoned segment with undefined
          behavior
    * `JsonParser.reify(Series<Char>)` parse and return the expression as nested maps and arrays and values
        * Js Arrays return as Series<Any?>, Js Objects return as Map<String,Any?> ; all Js Values return as Any?
        * for better or worse, non-string ParseDoubleOrNull not only does a cheap withotu string allocation costs but is
          also the source of parsed nulls when the Double parser falls through.
    * `JsonParser.jsPath(Series<Char>,JsPath)` ~~ghetto jq~~ will traverse the index to the depth of the path provided.
        * `JsPathElement` is an `Either<String,Int>` created by `List<*>::toJsPath()` extension function
        * optional reified param will return the value at the path reified as a kotlin type else just a segment JsIndex
        * String keys will abort on Arrays but Int keys will fetch the nth index from either a json object or Array


* [x] linux-biased Posix IO utils exist for kotlin-common, jvm, and native (linux only)
    * [ ] IO-Uring has been brought in and many tests ported, but not applied knowledgably as yet nor updated to keep
      current with liburing.

* [ ]  a handful of missing kotlin-common collections are scattered about, these would be about as warrantable as the
  unit tests you might find for them.

---- 

<sup>[#1]</sup>:   ISAM

- according to wikipedia, ISAM is not only a flat table file format but also a key-value index and a btree-ish
  or b+treeish layout for the vaguely flat description of data volumes.
- Columnar and TrikeShed have used ISAM to mean Indexed(as in array) Sequential Access Method, whereas perhaps   
  Array Indexed (as in array index) Random Access Flat Binary Storage or "AIRAFBS" would be the better acronym.
- Cursors, the "AIRAFBS" facade, are typealias from Series2->Series->Join with mixins matching the generic types at
  compile time. The combine function creates a top list of blocks (an Array or vararg of Series) and a sequential index
  of the lengths to redirect a lookup into. the Joined columns or combined Series being immutable, the lookups can
  behave like B+Tree but the block size makes no attempt at uniformity or balancing nodes. A chunked list iterator could
  approximate the layout of a B+Tree cheaply. 
    

