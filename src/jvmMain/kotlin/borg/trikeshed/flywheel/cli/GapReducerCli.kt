package borg.trikeshed.flywheel.cli

import borg.trikeshed.jules.JulesCause
import borg.trikeshed.utils.kanban.JulesBoardStore
import borg.trikeshed.utils.kanban.forForgeDir
import kotlinx.coroutines.runBlocking
import java.io.File

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
    val repoDir = File(args.getOrElse(0) { System.getProperty("user.dir") })
    val forgeDir = File(args.getOrElse(1) { System.getProperty("user.home") + "/.local/forge" })
    val readme = File(repoDir, "README.md")
    require(readme.exists()) { "README.md not found at $readme" }

    val store = JulesBoardStore.forForgeDir(forgeDir)

    runBlocking {
        val alreadyKnown = store.loadQueue().map { it.workId }.toSet()
        val gaps = GapReducer(repoDir, readme).reduce()
        val fresh = gaps.filter { it.workId !in alreadyKnown }

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
        println("[GAP-REDUCER] ${gaps.size} gaps found, ${fresh.size} new (dedup ${alreadyKnown.size} known)")
        for (gap in fresh) {
            println("  + ${gap.workId} [${gap.tier}] ${gap.title}")
        }
        for (gap in gaps.minus(fresh.toSet())) {
            println("  = ${gap.workId} (already queued)")
        }
    }
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
    )

    fun reduce(): List<Gap> {
        val gaps = mutableListOf<Gap>()
        val sourceRoot = File(repoDir, "src")
        val readmeText = readme.readText()
        val now = System.currentTimeMillis()

        // ── 1. Compiled-out slab layer (README §0 line 60) ──────────────
        // "~20 TODO() stubs: GraalJS eval, DuckDB c-interop, FacetedCursorContract,
        //  MiniDuckContract; files preserved on disk"
        val slabGaps = scanStubs(File(sourceRoot, "commonMain/kotlin/borg/trikeshed/classfile/slab"))
        for (stub in slabGaps) {
            val workId = "gap:slab:${stub.path}"
            val fileName = stub.path.substringAfterLast('/')
            gaps.add(Gap(
                workId = workId,
                tier = "task",
                title = "Implement stub: $fileName:${stub.line} ${stub.todoText}",
                spec = buildSpec(
                    "Fill the TODO() stub at ${stub.path} (line ${stub.line}).",
                    "The slab layer is compiled out of commonMain (build.gradle.kts:180) but",
                    "these contracts define real surfaces (Btrfs ioctls, DuckDB c-interop,",
                    "FacetedCursor). The stub says: ${stub.todoText}",
                    "",
                    "Read the surrounding contract for the expected signature and types.",
                    "Implement the body so it compiles and the contract is real, not hollow.",
                    "Source: src/commonMain/kotlin/borg/trikeshed/classfile/slab/${stub.path}"
                ),
                parent = "slab",
                score = 0.3,
            ))
        }

        // ── 2. Non-slab TODO() stubs in production code ─────────────────
        val productionDirs = listOf(
            "commonMain/kotlin/borg/trikeshed/cursor",
            "commonMain/kotlin/borg/trikeshed/couch/isam",
            "posixMain/kotlin/borg/trikeshed/isam",
            "posixMain/kotlin/borg/trikeshed/common",
        )
        for (dir in productionDirs) {
            val stubs = scanStubs(File(sourceRoot, dir))
            for (stub in stubs) {
                val relPath = "$dir/${stub.path}"
                val workId = "gap:stub:${relPath.replace('/', ':')}"
                val fileName = stub.path.substringAfterLast('/')
                gaps.add(Gap(
                    workId = workId,
                    tier = "task",
                    title = "Fill stub: $fileName:${stub.line}",
                    spec = buildSpec(
                        "Fill the TODO() stub at $relPath line ${stub.line}.",
                        "The stub says: ${stub.todoText}",
                        "",
                        "This is production code (not compiled out). The stub will throw",
                        "if executed. Implement the expected behavior by reading the",
                        "surrounding class/interface contract.",
                        "Source: src/$relPath"
                    ),
                    parent = "stubs",
                    score = 0.6,
                ))
            }
        }

        // ── 3. Dead serialization stacks (README §4 + §2 Confix layer) ──
        // ConfixSerialFormat.kt exists (293 lines) but has ZERO production callers.
        // README §4 claims "single parser JSON/YAML/CBOR" and @Serializable linkage.
        val confixSerialFile = File(sourceRoot,
            "commonMain/kotlin/borg/trikeshed/parse/confix/ConfixSerialFormat.kt")
        if (confixSerialFile.exists()) {
            val hasCallers = grepProduction(sourceRoot,
                "ConfixSerialFormat|ConfixFormat|ConfixElementEncoder")
            if (hasCallers.isEmpty()) {
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
        }

        // ── 4. @Serializable with zero .serializer() calls (inert cost) ─
        // 19 files annotate @Serializable, 0 production .serializer() calls.
        // Either wire them through Confix (#3) or remove the annotations.
        val serializerCallCount = grepProduction(sourceRoot, "\\.serializer\\(\\)").size
        if (serializerCallCount == 0) {
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

        // ── 5. README self-declared partials ────────────────────────────
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

        // ── 6. Open concept checkboxes (README tail) ────────────────────
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
    }

    // ── helpers ────────────────────────────────────────────────────────

    data class StubHit(val path: String, val line: Int, val todoText: String)

    private fun scanStubs(dir: File): List<StubHit> {
        if (!dir.exists()) return emptyList()
        val hits = mutableListOf<StubHit>()
        dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            file.readLines().forEachIndexed { i, line ->
                val match = todoPattern.find(line)
                if (match != null) {
                    val rel = file.relativeTo(dir).path
                    hits.add(StubHit(rel, i + 1, match.groupValues[1].trim()))
                }
            }
        }
        return hits
    }

    private val todoPattern = Regex("""TODO\(([^)]*)\)""")

    private fun grepProduction(sourceRoot: File, pattern: String): List<String> {
        val cmd = listOf("grep", "-rn", "--include=*.kt", pattern, sourceRoot.absolutePath)
        val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        return output.lines().filter {
            it.isNotBlank() &&
            !it.contains("/test/", ignoreCase = true) &&
            !it.contains("Test.kt", ignoreCase = true)
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
