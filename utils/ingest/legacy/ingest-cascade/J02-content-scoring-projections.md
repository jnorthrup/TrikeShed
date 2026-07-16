# J02 — Content Scoring Projections

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

Before writing any code, analyze the existing indicator and cursor structures you will build on:

1. Read `src/commonMain/kotlin/borg/trikeshed/indicator/FeatureExtractor.kt` — understand the existing feature extraction pattern. Note how it transforms series data into derived metrics.

2. Read `src/commonMain/kotlin/borg/trikeshed/indicator/DoubleSeries.kt` — understand how Double series are represented and operated on. This is the target type for cognitive-load and word-power scores.

3. Read `src/commonMain/kotlin/borg/trikeshed/cursor/Cursor.kt` and `src/commonMain/kotlin/borg/trikeshed/cursor/RowVecSupport.kt` — understand how Cursor rows work. Table extraction will project into `Cursor<RowVec>` rows that become `ForgeBlockKind.TABLE_ROW`.

4. Read `src/commonMain/kotlin/borg/trikeshed/forge/ForgeDoc.kt` — re-read `ForgeBlockKind` enum. Note that `TABLE`, `TABLE_ROW` already exist. The word-power and cognitive-load outputs will attach to TEXT blocks as metadata.

5. Read `src/commonMain/kotlin/borg/trikeshed/charstr/TextK.kt` and `src/commonMain/kotlin/borg/trikeshed/charstr/CharStr.kt` — understand how text is represented as series of characters. Content scoring will operate on `Series<Char>` input.

6. Read `src/commonMain/kotlin/borg/trikeshed/lib/Join.kt` lines 40-82 — confirm the `MetaSeries`, `Series`, `α` operator, and `get` operator shapes.

7. Search for existing scoring/analysis patterns: `grep -rn "score\|Score\|metric\|Metric\|cognitive\|taxonomy" src/commonMain/` — understand what already exists so you do not duplicate.

Report your findings as a comment block at the top of your first file, then proceed.

## Main task: Word-power, cognitive-load, and table-spatial projections as MetaSeries transforms

These are three pure-arithmetic scoring functions that take `Series<Char>` input and produce `Series<Score>` output. They are projections (`α` transforms) over extracted content. Zero external dependencies. All work in `src/commonMain/kotlin/borg/trikeshed/forge/ingest/scoring/`.

These formulas are ported from a Python project (`tika4all`) but the implementations are fresh Kotlin using the kernel algebra. No Python runtime, no numpy, no external libraries. Pure commonMain arithmetic.

### File 1: `src/commonMain/kotlin/borg/trikeshed/forge/ingest/scoring/CognitiveLoad.kt`

Port the cognitive-load formula. The formula measures text complexity using readability research factors:

```kotlin
package borg.trikeshed.forge.ingest.scoring

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.size
import borg.trikeshed.lib.j

/**
 * Cognitive load score for text content.
 * Based on readability factors: word count, unique word ratio,
 * average word length, sentence count, average sentence length,
 * punctuation density, numeric density, capital density.
 *
 * Returns a double in [0.0, ~10.0] where higher = more complex.
 */
object CognitiveLoad {

    /**
     * Calculate cognitive load over a Series<Char> text.
     * Uses the kernel α projection internally.
     */
    fun score(text: Series<Char>): Double {
        if (text.size == 0) return 0.0

        // Tokenize using Series views, not List materialization
        // Count words, sentences, punctuation, numbers, capitals
        // Implement word counting by scanning for whitespace boundaries
        // Implement sentence counting by scanning for [.!?]+ boundaries

        // Formula (ported from readability research):
        // load = wordCount * 0.1
        //      + (1 - uniqueRatio) * 2.0          // vocabulary diversity penalty
        //      + avgWordLength * 0.3
        //      + avgSentenceLength * 0.05
        //      + punctDensity * 1.5
        //      + numberDensity * 0.8
        //      + capitalDensity * 0.5

        TODO("J02: implement the scoring formula — pure arithmetic over Series<Char>")
    }

    /**
     * Per-word cognitive load as a Series — for projection into ForgeBlock metadata.
     */
    fun perWord(text: Series<Char>): Series<Double> {
        // tokenize into words as a Series<String>
        // score each word individually
        // return lazy Series<Double> via α projection
        TODO("J02: implement per-word scoring")
    }
}
```

### File 2: `src/commonMain/kotlin/borg/trikeshed/forge/ingest/scoring/WordPower.kt`

Port the word-power scoring formula. Each word gets a power score based on semantic weight (frequency-based), contextual relevance (position in document), and taxonomic category.

```kotlin
package borg.trikeshed.forge.ingest.scoring

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.α

/**
 * Word power scoring — measures the semantic weight, frequency importance,
 * and contextual relevance of each word in a document.
 *
 * Ported from tika4all taxonomical_analyzer.py PowerPerWordMetric.
 */
data class WordPowerMetric(
    val word: String,
    val powerScore: Double,
    val frequency: Int,
    val semanticWeight: Double,
    val contextualRelevance: Double,
    val taxonomicCategory: String,
    val positionImportance: Double,
)

object WordPower {

    /**
     * Score all words in text. Returns a Series<WordPowerMetric> — one per unique word.
     *
     * powerScore = semanticWeight * 0.4 + frequencyWeight * 0.3
     *            + contextualRelevance * 0.2 + positionImportance * 0.1
     *
     * semanticWeight = log(1 + frequency) / log(1 + totalWords)
     * frequencyWeight = frequency / maxFrequency
     * contextualRelevance = position-based: words in first/last 10% get boost
     * positionImportance = 1.0 - (|position - center| / center)
     */
    fun score(text: Series<Char>): Series<WordPowerMetric> {
        TODO("J02: implement word-power scoring — tokenize, count, score")
    }

    /**
     * Legal taxonomy patterns for category classification.
     * Each category is a set of regex patterns.
     */
    val taxonomyPatterns: Map<String, Series<String>> = _m(
        "constitutional" to _s["constitut*", "amendment", "bill of rights"],
        "contract" to _s["contract", "agreement", "consideration", "breach"],
        "property" to _s["property", "easement", "title", "possession"],
        "procedural" to _s["court", "jurisdiction", "venue", "standing"],
    )
    // NOTE: use _m for Map, _s for Set — verify these exist in the collection literals
    // If _m does not exist, use standard mapOf()
}
```

### File 3: `src/commonMain/kotlin/borg/trikeshed/forge/ingest/scoring/TableSpatial.kt`

Port the table spatial-to-structural inference. This takes OCR-like spatial text detections (x, y, width, height, text) and infers row/column structure to produce a Cursor suitable for ForgeBlockKind.TABLE_ROW.

```kotlin
package borg.trikeshed.forge.ingest.scoring

import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.Series

/**
 * Spatial text detection from OCR/extraction.
 */
data class TextDetection(
    val text: String,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val confidence: Double = 1.0,
)

/**
 * Table spatial-to-structural inference.
 * Groups TextDetections into rows by y-proximity, then into columns by x-alignment.
 * Produces a Cursor<RowVec> where each row is a table row.
 */
object TableSpatial {

    /**
     * Infer table structure from spatial text detections.
     *
     * Algorithm:
     * 1. Sort detections by y, then x.
     * 2. Group into rows: detections whose y-ranges overlap belong to the same row.
     * 3. Within each row, columns are determined by x-gap clustering.
     * 4. Filter rows below [confidenceThreshold].
     */
    fun inferTable(
        detections: Series<TextDetection>,
        confidenceThreshold: Double = 0.7,
    ): Cursor<RowVec> {
        TODO("J02: implement spatial table inference")
    }

    /**
     * Convert an inferred table Cursor to ForgeBlock TABLE_ROW blocks.
     * Each row in the Cursor becomes a ForgeBlock with kind TABLE_ROW.
     */
    fun toTableBlocks(table: Cursor<RowVec>): Series<TableBlock> {
        TODO("J02: implement Cursor → ForgeBlock projection")
    }
}

data class TableBlock(
    val rowIndex: Int,
    val columnIndex: Int,
    val text: String,
    val confidence: Double,
)
```

### Tests

Write tests in `src/commonTest/kotlin/borg/trikeshed/forge/ingest/scoring/`:

1. `CognitiveLoadTest.kt`:
   - Empty text returns 0.0
   - Simple text ("Hello world") returns a low score (< 3.0)
   - Complex legal text returns a higher score (> 5.0)
   - `perWord()` returns a Series whose size equals word count

2. `WordPowerTest.kt`:
   - Single repeated word has high frequency, high semanticWeight
   - First/last position words get contextualRelevance boost
   - Taxonomy patterns match expected categories
   - `score()` returns a Series<WordPowerMetric> with correct size

3. `TableSpatialTest.kt`:
   - Two detections at same y → same row
   - Two detections at different y → different rows
   - Detections below confidence threshold are filtered
   - `inferTable()` returns a non-empty Cursor
   - `toTableBlocks()` produces TableBlock objects

Use JUnit4 (`org.junit.Test`, `org.junit.Assert.*`) — NOT JUnit5. TrikeShed root project uses `kotlin("test-junit")`.

### Verification

1. `./gradlew compileKotlinJvm` passes.
2. `./gradlew :test --tests "borg.trikeshed.forge.ingest.scoring.*"` passes.
3. `grep -rn "CognitiveLoad\|WordPower\|TableSpatial" src/commonMain` shows the new types.
4. No numpy, no Python, no external math library imports — pure kotlin stdlib + kotlin.math.
5. No `expect`/`actual` declarations.
6. No `libs/` references.
7. PRELOAD compliance: all Series usage uses `α`, `j`, `size` — no `(0 until n).map` patterns. Use `for (x in series.view)` for iteration.
8. If J01 (ingest SPI contracts) has been merged, verify that `ProjectionKind.COGNITIVE_LOAD`, `ProjectionKind.TAXONOMY`, and `ProjectionKind.TABLE_EXTRACTION` reference these scoring modules. If J01 has NOT been merged yet, this is fine — the scoring modules are standalone.
