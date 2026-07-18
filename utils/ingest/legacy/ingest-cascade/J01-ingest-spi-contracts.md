# J01 — Ingest SPI Contracts

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

Before writing any new files, analyze the existing SPI and Forge structures:

1. Read `src/commonMain/kotlin/borg/trikeshed/userspace/nio/file/spi/FileTypeDetector.kt` — this is the existing file-type detection SPI. Note its shape: abstract class in commonMain, `probeContentType` method, platform implementations in jvmMain/jsMain/etc.

2. Read `src/commonMain/kotlin/borg/trikeshed/userspace/nio/file/spi/FileSystemProvider.kt` — note the SPI registration pattern (`installedProviders()` companion).

3. Read `src/commonMain/kotlin/borg/trikeshed/forge/ccek/ccek/CCEK.kt` — understand the CCEK reactor binding. Note `ForgeSignal` sealed class at line 64 and the existing signal subtypes (UpdateText, DeleteBlock, MoveCard). Note `inputChannel` and `fanOutChannel` factory methods.

4. Read `src/commonMain/kotlin/borg/trikeshed/forge/ForgeDoc.kt` — understand `ForgeBlock`, `ForgeBlockId`, `ForgeBlockKind` enum, and the block tree structure.

5. Read `src/commonMain/kotlin/borg/trikeshed/forge/ForgeApp.kt` — understand how the Forge application wires CCEK to the document model.

6. Search for existing ingest references: `grep -rn "ingest\|Ingest\|schedule\|Schedule" src/commonMain/kotlin/borg/trikeshed/forge/` — confirm the gap is real.

7. Read `src/commonMain/kotlin/borg/trikeshed/forge/ccek/ccek/CCEK.kt` lines 64-90 — understand how `ForgeSignal` flows through `signalIn: Channel<ForgeSignal>`.

Report your findings as a comment block at the top of your first file, then proceed.

## Main task: IngestSchedule SPI + IngestResult + ForgeSignal.IngestComplete

Create the ingest seam that makes Forge an open system. All work in `src/commonMain/kotlin/borg/trikeshed/forge/ingest/`. This is pure commonMain — no platform implementations, no execution logic, just the contract types.

### File 1: `src/commonMain/kotlin/borg/trikeshed/forge/ingest/IngestResult.kt`

Define the result of an ingest operation. It must carry:

```kotlin
@Serializable
data class IngestResult(
    val sourcePath: String,
    val mediaType: String,           // MIME-ish: "application/pdf", "audio/wav", "text/plain"
    val detectedFormat: String,      // Confix facet: "pdf", "archive-zip", "audio-whisper", "text-markdown"
    val extractedContent: Series<Char>,   // the actual extracted text/bytes as a Char Series
    val projections: Set<ProjectionKind>, // what projections were applied
    val qualityMetrics: Map<String, Double>, // confidence scores, extraction quality
    val processingTimeMs: Long,
    val blockIds: List<String>,      // ForgeBlockIds created from this ingest
)
```

Also define `ProjectionKind` as a sealed class or enum:
```kotlin
enum class ProjectionKind {
    TEXT_EXTRACTION,      // raw text out
    TABLE_EXTRACTION,     // structured tables → ForgeBlockKind.TABLE_ROW
    AUDIO_TRANSCRIPTION,  // speaker turns → ForgeBlockKind.TEXT
    TAXONOMY,             // word-power scoring → ForgeBlockKind.TEXT metadata
    COGNITIVE_LOAD,       // complexity scoring → quality metrics
    METADATA,             // file metadata only
}
```

### File 2: `src/commonMain/kotlin/borg/trikeshed/forge/ingest/IngestSchedule.kt`

Define the SPI interface for scheduling ingest jobs:

```kotlin
package borg.trikeshed.forge.ingest

import borg.trikeshed.userspace.nio.file.Path
import kotlinx.coroutines.channels.Channel

/**
 * SPI for scheduling media/format ingest into the Forge document tree.
 * Platform implementations register via the existing SPI provider pattern
 * (see userspace.nio.file.spi.FileSystemProvider).
 */
interface IngestSchedule {
    /**
     * Schedule an ingest job for [source].
     * Returns a Channel that receives IngestResult as each projection completes.
     * Non-blocking — the caller drains the channel at its own pace.
     */
    fun schedule(
        source: String,                    // path or URI to the media
        projections: Set<ProjectionKind>,  // which projections to apply
    ): Channel<IngestResult>

    /**
     * Schedule a batch of sources. Returns a single merged channel.
     */
    fun scheduleBatch(
        sources: Series<String>,
        projections: Set<ProjectionKind>,
    ): Channel<IngestResult> {
        // default implementation merges per-source channels
        TODO("J01: implement batch merge — fan-in N channels into one")
    }
}
```

IMPORTANT: Use `Series<String>` not `List<String>` for the batch parameter. Follow PRELOAD.

### File 3: Add `IngestComplete` to ForgeSignal

Edit `src/commonMain/kotlin/borg/trikeshed/forge/ccek/ccek/CCEK.kt` — add a new sealed subtype inside the `ForgeSignal` sealed class (currently around line 64-73):

```kotlin
data class IngestComplete(
    val result: IngestResult,
    val targetBlockId: String?,
) : ForgeSignal()
```

This is the ONLY edit to an existing file. Add the import for `borg.trikeshed.forge.ingest.IngestResult` at the top of CCEK.kt. Do not touch anything else in CCEK.kt.

### File 4: `src/commonMain/kotlin/borg/trikeshed/forge/ingest/MediaFormatChannel.kt`

Define the SPI for detecting media format and available projections. This extends the existing FileTypeDetector shape but adds Confix-aware facet projection:

```kotlin
package borg.trikeshed.forge.ingest

/**
 * SPI for media/format detection and projection routing.
 * A platform implementation probes a source and returns which
 * ProjectionKinds are available for that media type.
 *
 * This is the Confix-based access layer for media channels.
 * It does NOT execute extraction — it only describes what is possible.
 */
interface MediaFormatChannel {
    /** Detect the media type of a source path. */
    fun detect(path: String): MediaFormatInfo

    /** Return the projections available for a detected media type. */
    fun availableProjections(mediaType: String): Set<ProjectionKind>
}

data class MediaFormatInfo(
    val path: String,
    val mediaType: String,           // "application/pdf", "audio/wav"
    val formatFacet: String,         // Confix facet key: "pdf", "zip", "audio", "text"
    val confidence: Double,
    val availableProjections: Set<ProjectionKind>,
)
```

### Tests

Write tests in `src/commonTest/kotlin/borg/trikeshed/forge/ingest/`:

1. `IngestResultTest.kt` — verify IngestResult serialization round-trips, Series<Char> content is accessible via `result.extractedContent[i]`, ProjectionKind set operations work.

2. `MediaFormatInfoTest.kt` — verify MediaFormatInfo construction and that availableProjections returns sensible defaults per formatFacet.

3. `IngestScheduleContractTest.kt` — define a fake IngestSchedule implementation in the test that returns a Channel with test data. Verify: `schedule()` returns a Channel, the channel receives IngestResult objects, `scheduleBatch` default merges multiple sources.

### Verification

1. `./gradlew compileKotlinJvm` passes.
2. `./gradlew :test --tests "borg.trikeshed.forge.ingest.*"` passes.
3. `grep -rn "IngestSchedule\|IngestResult\|MediaFormatChannel\|ProjectionKind" src/commonMain` shows the new types.
4. `grep -rn "IngestComplete" src/commonMain/kotlin/borg/trikeshed/forge/ccek/` shows the new ForgeSignal subtype.
5. No `expect`/`actual` declarations anywhere in the new code.
6. No `libs/` references in any new file.
7. PRELOAD compliance: all Series usage uses `α`, `j`, `size` — no `(0 until n).map` patterns.
