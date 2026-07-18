# J05 — Drag-Drop Toolkit + Forge UI Integration

## Preamble (environment freeze — do this first, non-negotiable)

You are working in the TrikeShed repository at the project root. Before writing any code:

1. Verify JDK is GraalVM CE 25.0.2, NOT Amazon Corretto. Run `./gradlew --version` and check the daemon JVM path. If it shows `25.0.2-amzn` or any non-GraalVM JDK, STOP and report: "JDK mismatch — need GraalVM CE 25.0.2 via sdkman: sdk use java 25.0.2-graalce". Do not proceed on Corretto.

2. Run `./gradlew compileKotlinJvm` and confirm it passes on current HEAD before making changes. If it fails, capture the errors and report them — do not attempt to fix pre-existing failures.

3. NEVER create, modify, or reference the `libs/` directory. TrikeShed is root-only. All work goes in `src/commonMain/`. Historical `libs/` code is deleted permanently. Do not restore it, reference it, or port from it. If you need to inspect retired code, note its absence and implement fresh.

4. NEVER introduce `expect`/`actual` declarations. TrikeShed uses SPI interfaces in commonMain with platform implementations in jvmMain/jsMain/etc. The pattern is already established in `src/commonMain/kotlin/borg/trikeshed/userspace/nio/file/spi/` — follow that shape.

5. Use GraalVM polyglot APIs only where the existing code already uses them. Do not add new GraalVM dependencies to build.gradle.kts.

## PRELOAD contract (kernel algebra — all code must follow this)

Read PRELOAD.md at the repo root before writing any Kotlin. The kernel algebra is:

- `Join<A, B>` is the base binary composition. Everything composes from it.
- `Series<T> = Join<Int, (Int) -> T>` — size paired with an index function.
- `j` is the infix constructor: `a j b` creates a Join.
- `α` is lazy projection: `series α { transform }` maps without materializing.
- `↺` is left identity / constant anchor.
- `Cursor = Series<RowVec>` where `RowVec = Series2<Any, () -> RecordMeta>`.
- Collection literals: `_l[...]` for List, `_a[...]` for Array, `_s[...]` for Set, `s_[...]` for Series.

Rules:
- Composition over inheritance. Ranges and projections over mutable loops.
- Lazy views first; materialization later.
- Prefer `series α { it }` over `(0 until size).map { series[it] }`.
- Use `for (x in series.view)` not `for (i in 0 until series.size)`.
- Do not invent reification context machinery — common sense on projected size + mutability.
- `Series.toList()` is an AbstractList facade, no copy. Prefer over `view.toList()`.

## Context analysis precursor (do this before the main task)

This task integrates the ingest cascade into the Forge UI layer. Before writing any code, do a thorough analysis of the existing Forge UI and CCEK reactor:

1. Read `src/commonMain/kotlin/borg/trikeshed/forge/ForgeApp.kt` — understand the full Forge application. Note the JS/HTML rendering, the Compose Desktop entry, the state management. This is a large file — read it in chunks if needed. Pay attention to how blocks are rendered and how user interactions create ForgeSignals.

2. Read `src/commonMain/kotlin/borg/trikeshed/forge/ccek/ccek/CCEK.kt` — re-read the full CCEK reactor binding. Note the `ForgeSignal` sealed class, the `signalIn: Channel<ForgeSignal>`, the `ArticulatedNode`, and the `choreograph()` method. Understand how signals flow from user interaction to document mutation.

3. Read `src/commonMain/kotlin/borg/trikeshed/forge/ForgeDoc.kt` — understand `ForgeDocument`, `ForgeBlock`, `ForgeBlockKind` enum. Note all existing block kinds. The task adds new block kinds for media ingest.

4. Read `src/commonMain/kotlin/borg/trikeshed/forge/ForgePersistence.kt` — understand how Forge state is persisted. The HTML/JS rendering lives here. Note the `formatReactorEvent` and state hydration patterns.

5. Read `src/commonMain/kotlin/borg/trikeshed/forge/ccek/ccek/CcekScope.kt` and `CcekReactorBinding` (inside CCEK.kt) — understand the reactor scope and how child scopes are created.

6. Read `src/commonMain/kotlin/borg/trikeshed/forge/ccek/ccek/SupervisorJob.kt` — understand structured concurrency in CCEK.

7. Search for existing drag-drop or file-upload handling: `grep -rn "drag\|drop\|upload\|file.*input\|DataTransfer" src/` — check if any drag-drop exists.

8. Read `src/jsMain/` — scan for existing JS platform implementations of Forge or NIO that show how commonMain SPIs get platform wiring on the JS target.

9. Read `src/jvmMain/` — scan for existing JVM platform implementations showing how Compose Desktop wires commonMain types.

Report your findings as a comment block at the top of your first file, then proceed.

## Main task: Forge UI ingest integration — block kinds, CCEK binding, drag-drop

This task wires the ingest cascade (J01-J04) into the Forge UI. The cascade terminates here: drag-and-drop a file → IngestSchedule → MediaFormatChannel → IngestProjector → ForgeSignal.AppendBlock → ForgeDoc mutation. The CCEK reactor handles the signal routing.

This task depends on J01-J04 being merged. If they are not merged, this task defines the integration shapes against the current HEAD and notes which imports will need updating after merge. The code must compile against current HEAD.

### File 1: Extend ForgeBlockKind

Edit `src/commonMain/kotlin/borg/trikeshed/forge/ForgeDoc.kt` — add new block kinds to the `ForgeBlockKind` enum (currently around line 22):

```kotlin
// Add to existing enum:
    MEDIA,           // embedded media reference (image, audio, video)
    TRANSCRIPT,      // audio/video transcription with speaker turns
    TAXONOMY,        // word-power / taxonomic analysis output
    INGEST_SOURCE,   // reference to an ingested source corpus entry
```

This is a minimal edit — add 4 enum values. Do not touch other parts of ForgeDoc.kt.

### File 2: `src/commonMain/kotlin/borg/trikeshed/forge/ingest/IngestProjector.kt`

The projector takes IngestResult and produces ForgeBlocks. This is where the scoring projections (J02) and model routing (J03) are applied to produce the final block tree.

```kotlin
package borg.trikeshed.forge.ingest

import borg.trikeshed.forge.ForgeBlock
import borg.trikeshed.forge.ForgeBlockId
import borg.trikeshed.forge.ForgeBlockKind
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.α
import borg.trikeshed.lib.size
import borg.trikeshed.lib.j

/**
 * Projects an IngestResult into ForgeBlocks.
 * This is the terminal stage of the ingest cascade.
 *
 * For each IngestResult:
 * 1. Determine block kind from ProjectionKind
 * 2. Apply content scoring (if COGNITIVE_LOAD or TAXONOMY projection)
 * 3. Apply table inference (if TABLE_EXTRACTION projection)
 * 4. Create ForgeBlock tree
 */
object IngestProjector {

    /**
     * Project an IngestResult into a Series<ForgeBlock>.
     * The root block is MEDIA or TEXT; children are TABLE_ROW, TRANSCRIPT, etc.
     */
    fun project(result: IngestResult): Series<ForgeBlockCreation> {
        TODO("J05: implement IngestResult → ForgeBlock projection")
    }
}

/**
 * Intermediate representation for block creation.
 * Maps to ForgeBlock after parent linkage is resolved.
 */
data class ForgeBlockCreation(
    val kind: ForgeBlockKind,
    val text: String,
    val children: List<ForgeBlockCreation> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
)
```

### File 3: Add ingest signals to ForgeSignal

Edit `src/commonMain/kotlin/borg/trikeshed/forge/ccek/ccek/CCEK.kt` — add new ForgeSignal subtypes for the ingest cascade. If J01 already added `IngestComplete`, add these alongside it. If not, add all ingest signals together:

```kotlin
// Add to ForgeSignal sealed class:
    data class IngestComplete(
        val result: IngestResult,
        val targetBlockId: String?,
    ) : ForgeSignal()

    data class AppendBlocks(
        val blocks: Series<ForgeBlockCreation>,
        val parentId: String?,
    ) : ForgeSignal()
```

Add the import for `borg.trikeshed.forge.ingest.IngestResult` and `borg.trikeshed.forge.ingest.ForgeBlockCreation`.

IMPORTANT: Edit CCEK.kt surgically — add the import lines and the sealed class entries only. Do not touch the reactor binding, scope creation, or channel factory methods.

### File 4: `src/commonMain/kotlin/borg/trikeshed/forge/ingest/IngestDropHandler.kt`

The commonMain SPI for drag-drop ingest. Platform implementations (JS, JVM) translate native drop events into this interface.

```kotlin
package borg.trikeshed.forge.ingest

/**
 * SPI for handling drag-drop file ingest in the Forge UI.
 *
 * JS target: browser DataTransfer API → this interface
 * JVM target: Compose Desktop drop handler → this interface
 *
 * Both targets call IngestSchedule.schedule() with the dropped file paths.
 */
interface IngestDropHandler {

    /**
     * Handle a drop event containing one or more files.
     * Returns the channel that will receive ingest results.
     */
    fun handleDrop(
        filePaths: List<String>,
        targetBlockId: String?,
    ): kotlinx.coroutines.channels.Channel<IngestResult>

    /**
     * Determine if a dropped item is acceptable for ingest.
     */
    fun canAccept(path: String): Boolean
}

/**
 * Default implementation that wires drop events to IngestSchedule.
 */
class DefaultIngestDropHandler(
    private val schedule: IngestSchedule,
    private val formatChannel: MediaFormatChannel,
) : IngestDropHandler {

    override fun handleDrop(
        filePaths: List<String>,
        targetBlockId: String?,
    ): kotlinx.coroutines.channels.Channel<IngestResult> {
        // 1. Detect format for each file via MediaFormatChannel
        // 2. Determine projections from detected format
        // 3. Schedule ingest via IngestSchedule
        // 4. Return the result channel
        TODO("J05: implement drop → detect → schedule")
    }

    override fun canAccept(path: String): Boolean {
        // Use MediaFormatChannel to detect if the format is supported
        val info = formatChannel.detect(path)
        return info.availableProjections.isNotEmpty()
    }
}
```

### File 5: `src/commonMain/kotlin/borg/trikeshed/forge/ingest/IngestReactorBinding.kt`

Binds the ingest cascade to the CCEK reactor. This is where IngestResult flowing through a channel gets converted to ForgeSignal and dispatched into the reactor's signalIn channel.

```kotlin
package borg.trikeshed.forge.ingest

import borg.trikeshed.forge.ccek.ccek.CCEK
import borg.trikeshed.forge.ccek.ccek.ForgeSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Binds ingest result channels to the CCEK reactor.
 * Launches a coroutine that drains the ingest channel and
 * emits ForgeSignal.IngestComplete into the reactor.
 */
object IngestReactorBinding {

    /**
     * Bind an ingest result channel to the CCEK reactor.
     * The coroutine runs in the reactor's scope and drains
     * the channel, converting each result to a ForgeSignal.
     */
    fun bind(
        reactorScope: CoroutineScope,
        ingestChannel: Channel<IngestResult>,
        targetBlockId: String?,
        signalIn: Channel<ForgeSignal>,
    ) {
        reactorScope.launch {
            for (result in ingestChannel) {
                signalIn.send(ForgeSignal.IngestComplete(result, targetBlockId))
            }
        }
    }
}
```

### File 6 (optional): JS platform wiring stub

`src/jsMain/kotlin/borg/trikeshed/forge/ingest/JsIngestDropHandler.kt`

A minimal JS platform implementation that translates browser DataTransfer events into `IngestDropHandler.handleDrop()` calls. This does not need to be fully functional — it establishes the platform wiring pattern.

```kotlin
package borg.trikeshed.forge.ingest

import kotlinx.coroutines.channels.Channel
import kotlinx.browser.document
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.DragEvent
import org.w3c.files.File

/**
 * JS platform implementation of IngestDropHandler.
 * Translates browser drag-drop events into ingest schedule calls.
 */
class JsIngestDropHandler(
    private val delegate: DefaultIngestDropHandler,
) : IngestDropHandler {

    override fun handleDrop(
        filePaths: List<String>,
        targetBlockId: String?,
    ): Channel<IngestResult> = delegate.handleDrop(filePaths, targetBlockId)

    override fun canAccept(path: String): Boolean = delegate.canAccept(path)

    /**
     * Attach drag-drop listeners to the document.
     * Called during Forge UI initialization.
     */
    fun attachDragDrop(element: dynamic, onDrop: (List<String>) -> Unit) {
        TODO("J05: implement browser DataTransfer → file path extraction")
    }
}
```

### Tests

Write tests in `src/commonTest/kotlin/borg/trikeshed/forge/ingest/`:

1. `IngestProjectorTest.kt`:
   - `project()` with TEXT_EXTRACTION projection → creates TEXT block
   - `project()` with TABLE_EXTRACTION projection → creates TABLE + TABLE_ROW blocks
   - `project()` with AUDIO_TRANSCRIPTION → creates TRANSCRIPT block
   - `project()` with TAXONOMY → creates TAXONOMY block
   - Block tree parent-child linkage is correct

2. `IngestDropHandlerTest.kt`:
   - `canAccept` returns true for a known format ("test.pdf")
   - `canAccept` returns false for an unknown format ("test.xyz")
   - `handleDrop` returns a Channel (does not block)

3. `IngestReactorBindingTest.kt`:
   - Bind a channel, send an IngestResult, verify ForgeSignal.IngestComplete is received on signalIn
   - Channel close → coroutine completes without error

Use JUnit4 (`org.junit.Test`, `org.junit.Assert.*`) — NOT JUnit5.

### Verification

1. `./gradlew compileKotlinJvm` passes.
2. `./gradlew :test --tests "borg.trikeshed.forge.ingest.*"` passes (all J01-J05 tests).
3. `grep -rn "MEDIA\|TRANSCRIPT\|TAXONOMY\|INGEST_SOURCE" src/commonMain/kotlin/borg/trikeshed/forge/ForgeDoc.kt` shows new block kinds.
4. `grep -rn "IngestComplete\|AppendBlocks" src/commonMain/kotlin/borg/trikeshed/forge/ccek/` shows new signals.
5. `grep -rn "IngestProjector\|IngestDropHandler\|IngestReactorBinding" src/commonMain` shows new types.
6. No `expect`/`actual` declarations in commonMain code.
7. No `libs/` references.
8. PRELOAD compliance: all Series usage uses `α`, `j`, `size` — no `(0 until n).map` patterns.
9. If J01-J04 have been merged, verify that the full cascade compiles: IngestSchedule → MediaFormatChannel → IngestProjector → ForgeSignal.AppendBlocks. If not merged, each file should compile standalone with TODO stubs for cross-J dependencies.
