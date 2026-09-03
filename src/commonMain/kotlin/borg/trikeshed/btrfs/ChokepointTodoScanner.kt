package borg.trikeshed.btrfs

import borg.trikeshed.userspace.nio.file.spi.FileOperations

/**
 * Scans Kotlin sources for chokepoint TODOs — the per-target seams left in commonMain.
 *
 * Each TODO line like `TODO("jvmMain: FileChannel.lock …")` becomes a queue item.
 * Targets are parsed from the prefix before ':' (jvmMain, posixMain, linuxMain, js, wasm, common).
 * Multi-target TODOs (e.g. "jvmMain: … ; posixMain: …") are split into one item per target
 * so dispatch per target seam is explicit.
 */
object ChokepointTodoScanner {
    private val todoRegex = Regex("""TODO\s*\(\s*"([^"]+)"\s*\)""")
    private val targetRegex = Regex("""\b(jvmMain|posixMain|linuxMain|jvm|posix|linux|js|wasm|jsMain|wasmJs|common)\b\s*:""")

    data class RawTodo(val source: String, val line: Int, val rawDescription: String)

    fun scanFile(path: String, fileOps: FileOperations): List<RawTodo> {
        if (!fileOps.exists(path)) return emptyList()
        val text = fileOps.readString(path)
        val lines = text.lines()
        val out = mutableListOf<RawTodo>()
        for ((idx, line) in lines.withIndex()) {
            val m = todoRegex.find(line) ?: continue
            val desc = m.groupValues[1].trim()
            if (desc.isEmpty()) continue
            out.add(RawTodo(source = "$path:${idx + 1}", line = idx + 1, rawDescription = desc))
        }
        return out
    }

    fun scanFiles(paths: List<String>, fileOps: FileOperations): List<RawTodo> =
        paths.flatMap { scanFile(it, fileOps) }

    fun defaultScanPaths(): List<String> = listOf(
        "src/commonMain/kotlin/borg/trikeshed/btrfs/NioBtrfsGraalBlobStore.kt",
        "src/jvmMain/kotlin/borg/trikeshed/graal/subvm/TrikeShedGraalVfs.kt",
        "src/commonMain/kotlin/borg/trikeshed/dag/DagBitTreeSkeleton.kt",
        "src/commonMain/kotlin/borg/trikeshed/rdf/ConfixRdfAdapter.kt",
    )

    /** Split a raw TODO with multi-targets into per-target items. */
    fun expandTargets(raw: RawTodo): List<TodoQueueItem> {
        val desc = raw.rawDescription
        val targets = targetRegex.findAll(desc).mapTo(LinkedHashSet()) { it.groupValues[1] }.toList()
        if (targets.isEmpty()) {
            // no explicit target → common
            return listOf(TodoQueueItem(id = todoId(raw, "common"), target = "common", description = desc, source = raw.source, status = "pending"))
        }
        return targets.map { t ->
            // slice description for this target: take segment after "t:" up to next target or end
            val segment = extractSegment(desc, t) ?: desc
            TodoQueueItem(id = todoId(raw, t), target = normalizeTarget(t), description = segment.trim(), source = raw.source, status = "pending")
        }
    }

    private fun extractSegment(desc: String, target: String): String? {
        val pat = Regex("""\b${Regex.escape(target)}\b\s*:\s*""")
        val m = pat.find(desc) ?: return null
        val start = m.range.last + 1
        val next = targetRegex.find(desc, start)?.range?.first ?: desc.length
        return desc.substring(start, next).trim().trimEnd(';', ',')
    }

    private fun normalizeTarget(t: String): String = when (t) {
        "jvm", "jvmMain" -> "jvmMain"
        "posix", "posixMain" -> "posixMain"
        "linux", "linuxMain" -> "linuxMain"
        "js", "jsMain" -> "js"
        "wasm", "wasmJs" -> "wasm"
        else -> t
    }

    private fun todoId(raw: RawTodo, target: String): String {
        val base = "${raw.source}:$target:${raw.rawDescription.hashCode().toString(16)}"
        return base.replace(Regex("""[^A-Za-z0-9._:-]"""), "_")
    }
}

/** Queue item — file-backed durable. */
data class TodoQueueItem(
    val id: String,
    val target: String,
    val description: String,
    val source: String,
    val status: String, // pending | dispatched | failed
    val enqueuedAt: Long = 0L,
    val dispatchedAt: Long? = null,
)
