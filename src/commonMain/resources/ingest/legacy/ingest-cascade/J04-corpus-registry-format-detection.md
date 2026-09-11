# J04 — Corpus Registry + Format Detection

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

Before writing any code, analyze the existing ISAM, NIO, and HTTP reactor structures:

1. Read `src/commonMain/kotlin/borg/trikeshed/isam/IsamDataFile.kt` — understand ISAM indexed storage. The corpus registry will be ISAM-backed for persistent named collections.

2. Read `src/commonMain/kotlin/borg/trikeshed/isam/RecordMeta.kt` — understand record metadata. Corpus entries need RecordMeta for cursor compatibility.

3. Read `src/commonMain/kotlin/borg/trikeshed/couch/CouchStore.kt` — understand the Couch store pattern. Named document collections already exist here. The corpus registry can layer on top of CouchStore or ISAM directly.

4. Read `src/commonMain/kotlin/borg/trikeshed/couch/ConfixDocStore.kt` — understand Confix-backed document storage. This is the existing pattern for Confix-accessible persistent storage.

5. Read `src/commonMain/kotlin/borg/trikeshed/htx/HtxRequest.kt` — understand the HTTP reactor request shape. Archive.org metadata fetch will ride the existing Htx reactor, not a raw HTTP client.

6. Read `src/commonMain/kotlin/borg/trikeshed/userspace/nio/file/spi/FileTypeDetector.kt` — understand the file type detector SPI. Format detection extends this.

7. Search for existing corpus/collection abstractions: `grep -rn "corpus\|Corpus\|collection\|Collection" src/commonMain/kotlin/borg/trikeshed/forge/` — confirm the gap.

8. Read `src/commonMain/kotlin/borg/trikeshed/userspace/ByteRegion.kt` — understand byte region representation. ZIP range extraction uses byte ranges.

Report your findings as a comment block at the top of your first file, then proceed.

## Main task: Corpus registry, format priority scoring, archive metadata, ZIP range extraction

Port the corpus management and format detection patterns from tika4all. These are the access channels for media — how Forge discovers what content exists and decides what format it is. Pure commonMain interfaces + data models. No HTTP execution, no archive.org calls (those are platform SPI implementations).

The Python source (tika4all/corpus_manager.py, tika4all/archive_processor.py, tika4all/zip_range_extractor.py) defines:
- CorpusManager: named corpus collections with document enumeration
- Format priority scoring: .zip > .pdf > .tar > .7z > images
- Archive.org metadata fetch + format discovery
- ZIP byte-range extraction

We port the data models and interfaces. We do NOT port the requests-based HTTP calls (those become Htx reactor calls in a platform impl). We do NOT port remotezip (that becomes a platform SPI impl).

### File 1: `src/commonMain/kotlin/borg/trikeshed/forge/ingest/corpus/CorpusRegistry.kt`

```kotlin
package borg.trikeshed.forge.ingest.corpus

import borg.trikeshed.lib.Series

/**
 * A named corpus — a collection of documents available for ingest.
 */
data class CorpusEntry(
    val id: String,                    // unique identifier within the corpus
    val corpusName: String,            // which corpus this belongs to
    val title: String,
    val sourceUri: String,             // archive.org identifier, file path, URL
    val formatHints: Set<String>,      // detected/declared formats: "pdf", "zip", "txt"
    val metadata: Map<String, String>, // arbitrary metadata
)

/**
 * Registry of named corpora.
 * Each corpus provides a Series<CorpusEntry> of its documents.
 *
 * Ported from tika4all corpus_manager.py CorpusManager.
 * The registry itself is an SPI — platform implementations provide
 * actual corpus backing (ISAM, Couch, HTTP).
 */
interface CorpusRegistry {

    /** List all registered corpus names. */
    fun corpusNames(): Series<String>

    /** Get all entries in a named corpus. */
    fun entries(corpusName: String): Series<CorpusEntry>

    /** Find an entry by ID across all corpora. */
    fun findById(id: String): CorpusEntry? {
        // default: scan all corpora
        TODO("J04: implement cross-corpus lookup via Series view iteration")
    }

    /** Search entries by title substring. */
    fun searchByTitle(query: String): Series<CorpusEntry> {
        // default: linear scan + filter
        TODO("J04: implement title search")
    }
}
```

### File 2: `src/commonMain/kotlin/borg/trikeshed/forge/ingest/corpus/FormatPriority.kt`

Port the format priority scoring from tika4all archive_processor.py find_best_format. This determines which file format is best for OCR/extraction when multiple formats are available.

```kotlin
package borg.trikeshed.forge.ingest.corpus

import borg.trikeshed.lib.Series
import borg.trikelles.lib.size
import borg.trikeshed.lib.α

/**
 * Format priority scoring — determines the best available format
 * for extraction when a source has multiple format options.
 *
 * Ported from tika4all archive_processor.py find_best_format.
 * Priority: .zip (raw/source) > .pdf > .tar > .7z > .jpg/.jp2 > .tif
 */
object FormatPriority {

    /**
     * Format priority table. Higher weight = better for OCR/extraction.
     * The "raw" or "source" keyword in the filename adds +100 weight.
     */
    private val priorityTable: Series<FormatWeight> = s_[
        FormatWeight(".zip", 100, setOf("raw", "source")),
        FormatWeight(".pdf", 80),
        FormatWeight(".tar", 60),
        FormatWeight(".7z", 50),
        FormatWeight(".jp2", 40),
        FormatWeight(".jpg", 30),
        FormatWeight(".tif", 20),
    ]

    /**
     * Score a list of available files and return the best one for extraction.
     * Files with priority keywords ("raw", "source") get a boost.
     */
    fun selectBestFormat(
        availableFiles: Series<String>,
    ): FormatScore? {
        if (availableFiles.size == 0) return null

        // Score each file: base weight by extension + keyword boost
        // Return the highest scoring file
        // Use Series α projection — do NOT materialize a List
        TODO("J04: implement format priority scoring")
    }
}

data class FormatWeight(
    val extension: String,
    val baseWeight: Int,
    val boostKeywords: Set<String> = emptySet(),
)

data class FormatScore(
    val filename: String,
    val extension: String,
    val weight: Int,
    val hasBoostKeyword: Boolean,
)
```

### File 3: `src/commonMain/kotlin/borg/trikeshed/forge/ingest/corpus/ArchiveSource.kt`

Define the interface for archive.org metadata access. The actual HTTP calls are platform SPI implementations. This interface defines the contract.

```kotlin
package borg.trikeshed.forge.ingest.corpus

import borg.trikeshed.lib.Series

/**
 * Metadata for an archive.org item.
 * Ported from tika4all archive_processor.py get_archive_metadata shape.
 */
data class ArchiveMetadata(
    val identifier: String,
    val title: String,
    val description: String,
    val files: Series<ArchiveFile>,
    val year: Int?,
    val collection: String?,
    val mediatype: String?,
)

data class ArchiveFile(
    val name: String,
    val format: String,
    val size: Long,
    val source: String?,     // "raw", "derivative", etc.
)

/**
 * SPI for archive metadata access.
 * Platform implementations use the Htx reactor for actual HTTP.
 */
interface ArchiveSource {

    /** Fetch metadata for an archive.org identifier. */
    fun fetchMetadata(identifier: String): ArchiveMetadata

    /** List all files in an archive item. */
    fun listFiles(identifier: String): Series<ArchiveFile> {
        // default: fetchMetadata(identifier).files
        TODO("J04: default delegate")
    }
}
```

### File 4: `src/commonMain/kotlin/borg/trikeshed/forge/ingest/corpus/ZipRangeSource.kt`

Define the interface for ZIP byte-range extraction. This enables partial download of ZIP files — extract only the bytes you need from a specific file within the archive.

```kotlin
package borg.trikeshed.forge.ingest.corpus

import borg.trikeshed.userspace.ByteRegion

/**
 * SPI for extracting byte ranges from ZIP archives.
 * Ported from tika4all zip_range_extractor.py.
 *
 * Platform implementations use HTTP Range requests, RemoteZip,
 * or local file access depending on the source.
 */
interface ZipRangeSource {

    /**
     * Extract a byte range from a specific file within a ZIP archive.
     *
     * @param archiveUri  URI/path to the ZIP file
     * @param filename    Name of the file within the archive
     * @param start       Start byte offset
     * @param end         End byte offset (exclusive)
     * @return The extracted bytes, or null if the file/range doesn't exist
     */
    fun extractFileRange(
        archiveUri: String,
        filename: String,
        start: Long,
        end: Long,
    ): ByteArray?

    /**
     * List all files in a ZIP archive.
     */
    fun listFiles(archiveUri: String): Series<String>
}
```

### Tests

Write tests in `src/commonTest/kotlin/borg/trikeshed/forge/ingest/corpus/`:

1. `CorpusRegistryTest.kt`:
   - Implement a fake `CorpusRegistry` with hardcoded test data (2 corpora, 3 entries each)
   - `corpusNames()` returns both names
   - `entries("corpusA")` returns 3 entries
   - `findById()` finds entries across corpora
   - `searchByTitle("legal")` returns matching entries
   - Verify Series types are used (not List) in returns

2. `FormatPriorityTest.kt`:
   - `.zip` scores higher than `.pdf` for the same file
   - A file named "source_raw.zip" gets the keyword boost
   - `selectBestFormat` returns null on empty input
   - `selectBestFormat` returns the highest-weighted file
   - Unsupported extensions are ignored (or scored 0)

3. `ArchiveSourceTest.kt`:
   - Implement a fake `ArchiveSource` with hardcoded metadata
   - `fetchMetadata("lex-mercatoria")` returns metadata with files
   - `listFiles("lex-mercatoria")` delegates to `fetchMetadata().files`

4. `ZipRangeSourceTest.kt`:
   - Implement a fake `ZipRangeSource` with a small in-memory ZIP
   - `extractFileRange` returns the correct bytes for a known file/range
   - `extractFileRange` returns null for a nonexistent filename
   - `listFiles` returns the expected file list

Use JUnit4 (`org.junit.Test`, `org.junit.Assert.*`) — NOT JUnit5. TrikeShed root project uses `kotlin("test-junit")`.

### Verification

1. `./gradlew compileKotlinJvm` passes.
2. `./gradlew :test --tests "borg.trikeshed.forge.ingest.corpus.*"` passes.
3. `grep -rn "CorpusRegistry\|FormatPriority\|ArchiveSource\|ZipRangeSource" src/commonMain` shows the new types.
4. No `requests` import, no `remotezip` import, no HTTP client — pure SPI interfaces.
5. No `expect`/`actual` declarations.
6. No `libs/` references.
7. PRELOAD compliance: all Series usage uses `α`, `j`, `size`, `s_[]` — no `(0 until n).map` patterns.
8. If J01 has been merged, `MediaFormatChannel.detect()` may reference `FormatPriority.selectBestFormat()`. If not merged, compile standalone.
