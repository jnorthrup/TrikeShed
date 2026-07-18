# J03 — SRDF Model Genre + Performance Tracking

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

Before writing any code, analyze the existing model multiplexing and cursor storage structures:

1. Read `src/commonMain/kotlin/modelmux/ModelMux.kt` — understand the existing model multiplexer. Note how it routes between models. This is the integration target for genre-based routing.

2. Read `src/commonMain/kotlin/modelmux/acp/AcpProtocol.kt` — understand the agent communication protocol. Note the message shapes. Provider connections will ride this protocol.

3. Read `src/commonMain/kotlin/borg/trikeshed/userspace/reactor/ModelApiCache.kt` — understand how model API responses are cached. The performance tracking will extend or complement this.

4. Read `src/commonMain/kotlin/borg/trikeshed/cursor/Cursor.kt` — the performance store will be Cursor-backed. Each row is a model's performance record.

5. Read `src/commonMain/kotlin/borg/trikeshed/cursor/SimpleCursor.kt` — understand how simple cursors are constructed. The performance store is a simple in-memory cursor.

6. Read `src/commonMain/kotlin/borg/trikeshed/isam/RecordMeta.kt` — understand RecordMeta. Performance records need metadata for cursor compatibility.

7. Search for existing model/provider abstractions: `grep -rn "provider\|Provider\|genre\|Genre\|model.*select\|model.*route" src/commonMain/` — understand what exists so you do not duplicate.

Report your findings as a comment block at the top of your first file, then proceed.

## Main task: ModelGenre taxonomy + ModelPerformance Cursor-backed store

Port the SRDF (Self-Refining Data Flywheel) model genre taxonomy and performance tracking from tika4all. This is the quality flywheel: genre → model selection → performance tracking → feedback into genre→model selection. Pure commonMain data models, no HTTP clients, no provider connections. The integration target is `modelmux/ModelMux.kt`.

The Python source (tika4all/srdf_model_factory.py, tika4all/model_card_evaluator.py) defines:
- ModelGenre enum with 8 genres
- ModelPerformance dataclass tracking success_rate, avg_response_time, quality_score
- Provider connection data for zai/openrouter/gemini/nvidia/huggingface

We port the genre taxonomy and performance model. We do NOT port provider connections (those are platform-specific HTTP, belong in a future SPI implementation). We do NOT port model_card_evaluator (that was NVIDIA-specific quality scoring). We port the shape: genre enum, performance record, flywheel selection logic.

### File 1: `src/commonMain/kotlin/borg/trikeshed/forge/ingest/srdf/ModelGenre.kt`

```kotlin
package borg.trikeshed.forge.ingest.srdf

/**
 * Model genre taxonomy — determines which model family handles each ingest task type.
 * Ported from tika4all srdf_model_factory.py ModelGenre enum.
 */
enum class ModelGenre(val description: String) {
    FAST_TEXT("Quick text generation, summaries"),
    REASONING("Complex analysis, problem solving"),
    MULTIMODAL("Text + images, vision tasks"),
    LONG_CONTEXT("Large documents, extended context"),
    CODE("Programming, technical tasks"),
    CREATIVE("Creative writing, storytelling"),
    DOCUMENT_PROCESSING("OCR, table extraction"),
    AGENT_LLM("LLM for agent reasoning and chat"),
}
```

### File 2: `src/commonMain/kotlin/borg/trikeshed/forge/ingest/srdf/ModelPerformance.kt`

```kotlin
package borg.trikeshed.forge.ingest.srdf

import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.Series

/**
 * Performance record for a single model within a genre.
 * Ported from tika4all ModelPerformance dataclass.
 *
 * qualityScore = successRate * 0.5 + speedFactor * 0.3 + qualityFactor * 0.2
 * where speedFactor = clamp(1.0 - avgResponseTimeMs / targetTimeMs, 0.0, 1.0)
 */
data class ModelPerformance(
    val modelName: String,
    val genre: ModelGenre,
    val provider: String,
    val successRate: Double,        // [0.0, 1.0]
    val avgResponseTimeMs: Long,
    val qualityScore: Double,       // [0.0, 1.0] — SRDF-calculated
    val totalUses: Int,
    val lastUpdatedEpochMs: Long,
) {
    /**
     * Composite score for flywheel selection.
     * Higher = better. Range approximately [0.0, 1.0].
     */
    fun compositeScore(targetTimeMs: Long = 5000): Double {
        val speedFactor = (1.0 - avgResponseTimeMs.toDouble() / targetTimeMs).coerceIn(0.0, 1.0)
        return successRate * 0.5 + speedFactor * 0.3 + qualityScore * 0.2
    }
}
```

### File 3: `src/commonMain/kotlin/borg/trikeshed/forge/ingest/srdf/SrdfFlywheel.kt`

The flywheel: given a genre and a set of performance records, select the best model. Then update performance after use.

```kotlin
package borg.trikeshed.forge.ingest.srdf

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.size
import borg.trikeshed.lib.α
import borg.trikeshed.lib.j

/**
 * SRDF quality flywheel — selects the best model per genre
 * based on accumulated performance data.
 *
 * The flywheel is a read-only selection function over a Series<ModelPerformance>.
 * Updates are append-only: new performance records supersede old ones by (genre, modelName).
 */
object SrdfFlywheel {

    /**
     * Select the best model for [genre] from [records].
     * Returns null if no records exist for the genre.
     */
    fun selectBest(
        records: Series<ModelPerformance>,
        genre: ModelGenre,
        targetTimeMs: Long = 5000,
    ): ModelPerformance? {
        if (records.size == 0) return null

        // Filter to the genre using α projection + view filter
        // Find max compositeScore
        // Do NOT materialize a List — use Series view iteration
        TODO("J03: implement genre filtering + best-score selection")
    }

    /**
     * Update performance: given old records and a new observation,
     * produce the updated record set with the new observation folded in.
     *
     * If a record for (genre, modelName) exists, update it with a weighted average.
     * If not, append a new record.
     *
     * Returns a new Series<ModelPerformance> — the flywheel is immutable.
     */
    fun update(
        records: Series<ModelPerformance>,
        observation: ModelPerformance,
    ): Series<ModelPerformance> {
        TODO("J03: implement append-only performance update")
    }
}
```

### File 4: `src/commonMain/kotlin/borg/trikeshed/forge/ingest/srdf/GenreRoute.kt`

Map ingest projections to model genres. This is the bridge between the ingest SPI (J01) and the SRDF flywheel.

```kotlin
package borg.trikeshed.forge.ingest.srdf

import borg.trikeshed.forge.ingest.ProjectionKind

/**
 * Maps ingest projection kinds to model genres.
 * This is how the SRDF flywheel knows which genre to use for each ingest task.
 */
object GenreRoute {

    /**
     * Determine the model genre needed for a projection kind.
     * Some projections need specific model capabilities.
     */
    fun genreFor(projection: ProjectionKind): ModelGenre = when (projection) {
        ProjectionKind.TEXT_EXTRACTION      -> ModelGenre.DOCUMENT_PROCESSING
        ProjectionKind.TABLE_EXTRACTION     -> ModelGenre.MULTIMODAL
        ProjectionKind.AUDIO_TRANSCRIPTION  -> ModelGenre.MULTIMODAL
        ProjectionKind.TAXONOMY             -> ModelGenre.REASONING
        ProjectionKind.COGNITIVE_LOAD       -> ModelGenre.FAST_TEXT
        ProjectionKind.METADATA             -> ModelGenre.FAST_TEXT
    }

    /**
     * Determine all genres needed for a set of projections.
     */
    fun genresFor(projections: Set<ProjectionKind>): Set<ModelGenre> =
        projections.map { genreFor(it) }.toSet()
}
```

IMPORTANT: This file imports from J01's `ProjectionKind`. If J01 has not been merged yet, define a local `ProjectionKind` stub in the file comment and note the dependency. The code should compile standalone but the GenreRoute integration will need J01.

### Tests

Write tests in `src/commonTest/kotlin/borg/trikeshed/forge/ingest/srdf/`:

1. `ModelGenreTest.kt`:
   - All 8 genres exist and have non-empty descriptions
   - `GenreRoute.genreFor` maps each ProjectionKind to expected genre

2. `ModelPerformanceTest.kt`:
   - `compositeScore` returns values in [0, 1] range
   - Faster model with same success rate gets higher score
   - Higher quality score gets higher composite
   - Edge case: 0 response time → speedFactor = 1.0

3. `SrdfFlywheelTest.kt`:
   - `selectBest` returns null on empty records
   - `selectBest` returns the highest composite score for the genre
   - `selectBest` filters by genre (does not return a model from a different genre)
   - `update` creates a new record when none exists for (genre, model)
   - `update` averages existing record with new observation
   - `update` preserves immutability (original records unchanged)

4. `GenreRouteTest.kt`:
   - Each ProjectionKind maps to a genre
   - `genresFor` returns a set covering all projection genres

Use JUnit4 (`org.junit.Test`, `org.junit.Assert.*`) — NOT JUnit5. TrikeShed root project uses `kotlin("test-junit")`.

### Verification

1. `./gradlew compileKotlinJvm` passes.
2. `./gradlew :test --tests "borg.trikeshed.forge.ingest.srdf.*"` passes.
3. `grep -rn "ModelGenre\|ModelPerformance\|SrdfFlywheel\|GenreRoute" src/commonMain` shows the new types.
4. No HTTP client, no provider connection, no API key references — pure data models.
5. No `expect`/`actual` declarations.
6. No `libs/` references.
7. PRELOAD compliance: all Series usage uses `α`, `j`, `size` — no `(0 until n).map` patterns. The flywheel's `selectBest` must use Series views, not List materialization. The `update` function must construct the new Series using `size j { i -> ... }` not `listOf(...).toSeries()`.
