package borg.trikeshed.flywheel.cli

import borg.trikeshed.job.ContentId
import borg.trikeshed.jules.JulesCause
import borg.trikeshed.util.oroboros.LexicalMemory
import borg.trikeshed.util.oroboros.MergeReceipt
import borg.trikeshed.utils.kanban.JulesBoardStore
import borg.trikeshed.utils.kanban.forForgeDir
import kotlinx.coroutines.runBlocking
import java.io.File
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.toSeries
import kotlin.system.exitProcess

/**
 * Gap reducer — the flywheel's intake producer.
 *
 * Reads README.md concept claims (§0–§8), diffs each against the actual codebase,
 * and appends a [JulesCause.WorkQueued] for every claim that is HOLLOW, STUB, or
 * PARTIAL — but only for workIds not already queued/dispatched/drained.
 *
 * This is the single root of the kanban: README claims reduce into tasks.
 * Each landed task updates README, which regenerates new gaps. Infinite.
 *
 * Usage:
 *   GapReducerCli <repoDir> <forgeDir>
 *
 * Defaults: repoDir=cwd, forgeDir=~/.local/forge
 *
 * Gap severity → tier mapping:
 *   HOLLOW   → feature  (claim exists, implementation absent — highest leverage)
 *   STUB     → task     (TODO()/error() where real logic should be)
 *   PARTIAL  → chore    (works but incomplete/has open edges documented in README)
 */
fun main(args: Array<String>) {
    // 1. Reads JSON input
    val isLegacyMode = args.size != 1 && args.getOrNull(0) != "-"
<<<<<<< HEAD
    
=======

>>>>>>> origin/fix/pass-json-string-confixdoc-1593740257197449382
    val inputSeries: Series<Double>? = if (!isLegacyMode) {
        val jsonSource = if (args[0] == "-") readln() else File(args[0]).readText()
        // 2. Parses it into a Series<Double> using Confix
        val parsedList = JsonSupport.parse(jsonSource) as? List<*>
        (parsedList ?: emptyList<Any>()).mapNotNull {
            when (it) {
                is Number -> it.toDouble()
                is String -> it.toDoubleOrNull()
                else -> null
            }
        }.toSeries()
    } else {
        null
    }

    val repoDir = if (isLegacyMode) File(args.getOrElse(0) { System.getProperty("user.dir") }) else File(System.getProperty("user.dir"))
    val forgeDir = if (isLegacyMode) File(args.getOrElse(1) { System.getProperty("user.home") + "/.local/forge" }) else File(System.getProperty("user.home") + "/.local/forge")
    val readme = File(repoDir, "README.md")
    require(readme.exists()) { "README.md not found at $readme" }

    val store = JulesBoardStore.forForgeDir(forgeDir)

    runBlocking {
        val queueBefore = store.loadQueue()
        val gaps = GapReducer(repoDir, readme).reduce()
        val replacementByOldWorkId = buildMap {
            for (gap in gaps) {
                for (oldWorkId in gap.supersedes) put(oldWorkId, gap)
            }
        }
        val revision = gitHead(repoDir)
        val now = System.currentTimeMillis()
        val superseded = queueBefore.mapNotNull { entry ->
            val replacement = replacementByOldWorkId[entry.workId] ?: return@mapNotNull null
            if (entry.isDispatched || entry.isDrained) return@mapNotNull null
            entry to replacement
        }
        for ((entry, replacement) in superseded) {
            val receipt = MergeReceipt(
                workId = entry.workId,
                producer = "gap-reducer-superseded",
                producerRef = replacement.workId,
                patchCid = ContentId.of("${entry.workId}->${replacement.workId}".encodeToByteArray()),
                revision = revision,
                versionTag = replacement.workId,
                lexicalMemory = LexicalMemory(
                    summary = "Superseded by dependency-local RGA context",
                    title = entry.title,
                    content = replacement.title,
                ),
                claimedAt = now,
            )
            store.appendWork(entry.workId, JulesCause.WorkDrained(
                workId = entry.workId,
                sessionId = "superseded:${entry.workId}",
                commitSha = revision,
                taskId = replacement.workId,
                receipt = receipt,
                at = now,
            ))
        }

        val queue = store.loadQueue()
        val alreadyKnown = queue.map { it.workId }.toSet()
        val activeWorkIds = queue.asSequence()
            .filter { it.isDispatched && !it.isDrained }
            .map { it.workId }
            .toSet()
        val openGroupParents = queue.asSequence()
            .filter { !it.isDrained && it.workId.startsWith("gap:rga:") }
            .mapNotNull { it.parent }
            .toSet()
        val fresh = gaps.asSequence()
            .filter { it.workId !in alreadyKnown }
            .filter { gap ->
                gap.supersedes.isEmpty() ||
                    (gap.parent !in openGroupParents && gap.supersedes.none { it in activeWorkIds })
            }
            // One task per locality per reducer pass. The next pass reads the
            // landed code and emits the next current slice for that locality.
            .distinctBy { gap -> if (gap.supersedes.isEmpty()) gap.workId else gap.parent }
            .toList()

        for (gap in fresh) {
            store.appendWork(gap.workId, JulesCause.WorkQueued(
                workId = gap.workId,
                tier = gap.tier,
                title = gap.title,
                spec = gap.spec,
                parent = gap.parent,
                score = gap.score,
                at = System.currentTimeMillis(),
            ))
        }
        val deferred = gaps.count { gap -> gap.workId !in alreadyKnown && gap !in fresh }
        println(
            "[GAP-REDUCER] ${gaps.size} current gaps, ${fresh.size} new, " +
                "${superseded.size} single-file entries superseded, $deferred locality slices deferred " +
                "(dedup ${alreadyKnown.size} known)"
        )
        for ((entry, replacement) in superseded) {
            println("  > ${entry.workId} -> ${replacement.parent}")
        }
        for (gap in fresh) {
            println("  + ${gap.workId} [${gap.tier}] ${gap.title}")
        }
        for (gap in gaps.filter { it.workId in alreadyKnown }) {
            println("  = ${gap.workId} (already queued)")
        }
<<<<<<< HEAD
        
=======

>>>>>>> origin/fix/pass-json-string-confixdoc-1593740257197449382
        if (inputSeries != null) {
            // 3. Calls the core logic
            val result = GapReducer(repoDir, readme).reduce()
            // 4. Serialises the result back to JSON and prints it
<<<<<<< HEAD
            val resultJson = JsonSupport.stringify(result.map { 
=======
            val resultJson = JsonSupport.stringify(result.map {
>>>>>>> origin/fix/pass-json-string-confixdoc-1593740257197449382
                mapOf(
                    "workId" to it.workId,
                    "tier" to it.tier,
                    "title" to it.title,
                    "spec" to it.spec,
                    "parent" to it.parent,
                    "score" to it.score,
                    "supersedes" to it.supersedes.toList()
                )
            })
            println("Reducer output:")
            println(resultJson)
        }
    }
}

private fun printUsage() {
    println("Usage: GapReducerCli <path/to/file.json | ->")
}

private fun gitHead(repoDir: File): String = try {
    val process = ProcessBuilder("git", "rev-parse", "HEAD")
        .directory(repoDir)
        .redirectErrorStream(true)
        .start()
    val finished = process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
    if (finished && process.exitValue() == 0) {
        process.inputStream.bufferedReader().readText().trim()
    } else {
        if (!finished) process.destroyForcibly()
        "unknown"
    }
} catch (_: Throwable) {
    "unknown"
}

/**
 * Scans README.md for concept claims, diffs each against the codebase.
 *
 * Detection strategy — simple and honest:
 * 1. Parse README §2 (architecture spine) layer names and §1 kernel claims.
 * 2. For each claimed symbol/concept, grep the source tree for evidence.
 * 3. Classify: IMPLEMENTED (real callers), HOLLOW (files exist but no real
 *    wiring), STUB (TODO()/error()), PARTIAL (exists but README itself
 *    says "not yet implemented" or similar).
 */
class GapReducer(
    private val repoDir: File,
    private val readme: File,
) {
    data class Gap(
        val workId: String,
        val tier: String,
        val title: String,
        val spec: String,
        val parent: String? = null,
        val score: Double,
        /** File-level workIds this dependency-local context replaces. */
        val supersedes: Set<String> = emptySet(),
    )

    fun reduce(): List<Gap> {
        val gaps = mutableListOf<Gap>()
        val sourceRoot = File(repoDir, "src")
        val readmeText = readme.readText()
        val reducerSource = File(repoDir, "src/jvmMain/kotlin/borg/trikeshed/flywheel/cli/GapReducerCli.kt")

        // The classfile/slab contracts are deliberately excluded from commonMain
        // (build.gradle.kts:176-180), so they are not dispatchable product work.

        // ── 1. Current production TODO gaps, grouped by dependency locality ──
        // Read each production root exactly once. The old nested-directory walk
        // emitted one work item per file and dispatched a 15-agent thundering herd
        // into userspace/nio/file/attribute. Context groups serialize one coherent
        // concept at a time and their fingerprint changes as landed work removes
        // TODOs, so the next reducer pass starts from current progress.
        val productionRoots = sourceRoot.listFiles()
            ?.filter { it.isDirectory && it.name.endsWith("Main") }
            ?.map { File(it, "kotlin") }
            ?.filter { it.exists() }
            ?: emptyList()
        val stubs = productionRoots.flatMap { root ->
            scanStubs(root).map { stub ->
                stub.copy(path = root.relativeTo(sourceRoot).path + "/" + stub.path)
            }
        }.distinctBy { "${it.path}:${it.line}:${it.source}" }

        for ((context, contextStubs) in stubs.groupBy(::stubContext).entries.sortedBy { it.key.key }) {
            val fileChunks = contextStubs.map { it.path }.distinct().sorted().chunked(MAX_CONTEXT_FILES)
            for ((chunkIndex, chunkFiles) in fileChunks.withIndex()) {
                val chunkStubs = contextStubs.filter { it.path in chunkFiles }
                val signature = chunkStubs.sortedWith(compareBy(StubHit::path, StubHit::source))
                    .joinToString("\n") { "${it.path}:${it.source}" }
                val fingerprint = ContentId.of(signature.encodeToByteArray()).hex.take(12)
                val slice = if (fileChunks.size == 1) context.key else "${context.key}-${chunkIndex + 1}"
                val evidence = chunkStubs.groupBy { it.path }.entries
                    .sortedBy { it.key }
                    .joinToString("\n") { (path, hits) ->
                        "src/$path: TODO lines ${hits.map { it.line }.distinct().sorted().joinToString(",")}"
                    }
                val contractRoots = if (context.key == "nio-spi") {
                    listOf(
                        "src/commonMain/kotlin/borg/trikeshed/userspace/nio/file/spi/FileOperations.kt",
                        "src/commonMain/kotlin/borg/trikeshed/userspace/nio/file/spi/FileSystemProvider.kt",
                        "src/commonMain/kotlin/borg/trikeshed/userspace/nio/channels/spi/SelectorProvider.kt",
                        "src/commonMain/kotlin/borg/trikeshed/userspace/nio/channels/spi/AsynchronousChannelProvider.kt",
                        "src/*Main/kotlin/borg/trikeshed/userspace/nio/spi/PlatformProviders.*.kt",
                    ).joinToString("\n")
                } else {
                    "See the listed production definitions and callers."
                }
                val implementationRule = if (context.key == "nio-spi") {
                    "Do not implement leaf file/channel facades independently. Make them delegate through the commonMain SPI; platform providers own execution."
                } else {
                    "Implement the basic concept coherently across the listed files."
                }
                val supersedes = chunkFiles.mapTo(mutableSetOf()) { path ->
                    "gap:stub:${path.replace('/', ':')}"
                }
                gaps.add(Gap(
                    workId = "gap:rga:$slice:$fingerprint",
                    tier = "task",
                    title = "RGA ${context.title}: ${chunkFiles.size} files, ${chunkStubs.size} current gaps",
                    spec = buildSpec(
                        "RGA dependency-local context: ${context.title}",
                        "README concept claim: ${context.claim}",
                        "",
                        "Current executable evidence (TODO, not finished):",
                        evidence,
                        "",
                        "Contract roots:",
                        contractRoots,
                        "",
                        "Read the current definitions, compositions, and every production caller",
                        "before editing. $implementationRule Preserve members that prior agents",
                        "already finished. Do not expand outside this exact file set. Update README",
                        "only if the concept is now real.",
                        "Verification: ./gradlew :jvmMainClasses --no-daemon"
                    ),
                    parent = "rga:${context.key}",
                    score = 0.65,
                    supersedes = supersedes,
                ))
            }
        }

        // ── 2. Dead serialization stacks (README §4 + §2 Confix layer) ──
        // ConfixSerialFormat.kt exists (293 lines) but has ZERO production callers.
        // README §4 claims "single parser JSON/YAML/CBOR" and @Serializable linkage.
        val confixSerialFile = File(sourceRoot,
            "commonMain/kotlin/borg/trikeshed/parse/confix/ConfixSerialFormat.kt")
        val confixNeedsWiring = confixSerialFile.exists() &&
            grepProduction(
                sourceRoot,
                "ConfixSerialFormat|ConfixFormat|ConfixElementEncoder",
                excludedFiles = setOf(confixSerialFile, reducerSource),
            ).isEmpty()
        if (confixNeedsWiring) {
            gaps.add(Gap(
                    workId = "gap:hollow:confix-serial-format",
                    tier = "feature",
                    title = "Wire ConfixSerialFormat into the serialization pipeline",
                    spec = buildSpec(
                        "ConfixSerialFormat.kt (293 lines) implements the @Serializable",
                        "↔ Confix bridge (ConfixElementEncoder/Decoder, ConfixFormat",
                        "BinaryFormat) but has ZERO production callers.",
                        "",
                        "README §4 claims a single Confix parser (JSON/YAML/CBOR). The",
                        "@Serializable annotations across 19 production files generate",
                        "inert code because nothing calls .serializer() through Confix.",
                        "",
                        "Wire ConfixFormat as a real BinaryFormat entry point — connect it",
                        "to the Job Nexus CBOR path (CanonicalCbor) or the kanban CBOR path",
                        "so @Serializable types round-trip through Confix.",
                        "Source: src/commonMain/kotlin/borg/trikeshed/parse/confix/ConfixSerialFormat.kt"
                    ),
                    parent = "confix",
                    score = 0.8,
            ))
        }

        // ── 3. @Serializable with zero .serializer() calls (inert cost) ─
        // 19 files annotate @Serializable, 0 production .serializer() calls.
        // Either wire them through Confix (#3) or remove the annotations.
        val serializerCallCount = grepProduction(
            sourceRoot,
            "\\.serializer\\(\\)",
            excludedFiles = setOf(reducerSource),
        ).size
        if (!confixNeedsWiring && serializerCallCount == 0) {
            gaps.add(Gap(
                workId = "gap:hollow:serializable-annotations-inert",
                tier = "chore",
                title = "Resolve @Serializable annotations: wire or remove",
                spec = buildSpec(
                    "19 production files use @Serializable but ZERO production code",
                    "calls .serializer(). The annotations generate dead code (the",
                    "compiler plugin emits SerialDescriptor trees nobody reads).",
                    "",
                    "Either: (a) wire them through ConfixSerialFormat (see",
                    "gap:hollow:confix-serial-format), or (b) remove the annotations",
                    "if Confix owns serialization and kotlinx is never the format.",
                    "",
                    "Do NOT add new serialization machinery. Pick (a) or (b) per type.",
                    "Files: grep -rl '@Serializable' src/ | grep -v Test"
                ),
                parent = "confix",
                score = 0.4,
            ))
        }

        // ── 4. README self-declared partials ────────────────────────────
        // §3.4 CouchHeadProjection: "CID-derived _id/_rev not yet implemented"
        if (readmeText.contains("not yet implemented", ignoreCase = true)) {
            val partials = extractReadmePartials(readmeText)
            for (p in partials) {
                val workId = "gap:partial:${p.slug}"
                gaps.add(Gap(
                    workId = workId,
                    tier = "chore",
                    title = "Complete partial: ${p.description}",
                    spec = buildSpec(
                        "README declares this as not-yet-implemented:",
                        "\"${p.description}\"",
                        "",
                        "Read the surrounding README section for the intended behavior.",
                        "Implement it, then update the README to remove the qualifier.",
                        "Source: README.md (search for the quoted text)"
                    ),
                    parent = "readme-partials",
                    score = 0.5,
                ))
            }
        }

        // ── 5. Open concept checkboxes (README tail) ────────────────────
        val openCheckboxes = extractOpenCheckboxes(readmeText)
        for (cb in openCheckboxes) {
            val workId = "gap:checkbox:${cb.slug}"
            gaps.add(Gap(
                workId = workId,
                tier = "feature",
                title = cb.title,
                spec = buildSpec(
                    "README concept checkbox (open):",
                    cb.text,
                    "",
                    "Read the surrounding README section for full context.",
                    "Implement the concept, update README to [x] when landed.",
                    "Source: README.md (checkbox section)"
                ),
                parent = "readme-concepts",
                score = 0.7,
            ))
        }

        return gaps.sortedByDescending { it.score }
            .distinctBy { it.workId }
    }

    // ── helpers ────────────────────────────────────────────────────────

    data class StubHit(
        val path: String,
        val line: Int,
        val source: String,
    )

    private data class StubContext(
        val key: String,
        val title: String,
        val claim: String,
    )

    private fun scanStubs(dir: File): List<StubHit> {
        if (!dir.exists()) return emptyList()
        val hits = mutableListOf<StubHit>()
        dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            // Slab stubs are compiled out (build.gradle.kts) — not dispatchable work.
            if (file.path.contains("/slab/")) return@forEach
            file.readLines().forEachIndexed { i, line ->
                val trimmed = line.trimStart()
                if (trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")) {
                    return@forEachIndexed
                }
                if (todoPattern.containsMatchIn(line)) {
                    val rel = file.relativeTo(dir).path
                    hits.add(StubHit(rel, i + 1, line.trim()))
                }
            }
        }
        return hits
    }

    private val todoPattern = Regex("""\bTODO\s*\(""")

    private fun stubContext(stub: StubHit): StubContext {
        val path = stub.path
        if ("/userspace/nio/" in path) {
            return StubContext(
                key = "nio-spi",
                title = "commonMain userspace NIO SPI conformance",
                claim = "README.md:696 claims portable Posix IO; every userspace NIO facade must compose through the commonMain provider SPI before platform execution",
            )
        }
        val packagePath = path.substringBeforeLast('/').substringAfter("kotlin/")
        return StubContext(
            key = "pkg-" + packagePath.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').take(64),
            title = packagePath.substringAfterLast('/').ifBlank { "production package" },
            claim = "README concept map requires executable production behavior, not TODO facades",
        )
    }

    private companion object {
        const val MAX_CONTEXT_FILES = 6
    }

    private fun grepProduction(
        sourceRoot: File,
        pattern: String,
        excludedFiles: Set<File> = emptySet(),
    ): List<String> {
        val cmd = listOf("grep", "-rn", "--include=*.kt", pattern, sourceRoot.absolutePath)
        val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        val excludedPaths = excludedFiles.map { it.absolutePath }.toSet()
        val productionSourceSets = setOf(
            "commonMain", "jvmMain", "posixMain", "linuxMain", "nativeMain",
            "macosMain", "jsMain", "wasmJsMain", "androidMain",
        )
        return output.lines().filter { line ->
            val sourcePath = line.substringBefore(':')
            line.isNotBlank() &&
                sourcePath !in excludedPaths &&
                productionSourceSets.any { sourcePath.contains("/$it/") }
        }
    }

    data class ReadmePartial(val slug: String, val description: String)

    private fun extractReadmePartials(text: String): List<ReadmePartial> {
        val partials = mutableListOf<ReadmePartial>()
        val pattern = Regex("""(?i)([^\n]*not\s+yet\s+implemented[^\n]*)""")
        for (m in pattern.findAll(text)) {
            val desc = m.groupValues[1].trim().take(120)
            val slug = desc.lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
                .take(60)
            partials.add(ReadmePartial(slug, desc))
        }
        return partials.distinctBy { it.slug }
    }

    data class OpenCheckbox(val slug: String, val title: String, val text: String)

    private fun extractOpenCheckboxes(text: String): List<OpenCheckbox> {
        val boxes = mutableListOf<OpenCheckbox>()
        val pattern = Regex("""(?m)^\s*[-*]\s+\[\s*\]\s+(.+)""")
        for (m in pattern.findAll(text)) {
            val raw = m.groupValues[1].trim()
            val title = raw.take(80)
            val slug = "cb-" + raw.lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
                .take(50)
            boxes.add(OpenCheckbox(slug, title, raw.take(400)))
        }
        return boxes.distinctBy { it.slug }
    }

    private fun buildSpec(vararg lines: String): String =
        lines.joinToString("\n").take(3800) // under SPEC_BYTE_LIMIT (4000)
}
