package borg.trikeshed.util.oroboros

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import java.io.File

/**
 * Streams selected session-export records into Oroboros CAS and projects a
 * deterministic evidence manifest. The transcript is evidence; Git remains the
 * authority for committed bytes and producer activity remains mutable until a
 * merge receipt claims it.
 */
object FlywheelHistoryReaper {
    private val filePathPattern = Regex("(?:src|doc|bin)/[A-Za-z0-9_./-]+")
    private val revisionPattern = Regex("(?<![0-9a-f])[0-9a-f]{9,40}(?![0-9a-f])")
    private val producerPattern = Regex("sessions/[0-9]{15,22}")
    private val versionTagPattern = Regex("flywheel/[A-Za-z0-9._/-]+")

    data class ReapSummary(
        val receipts: Series<ExposureReceipt>,
        val manifest: File,
    )

    fun reap(
        export: File,
        home: File,
        selectedSessions: Set<String>,
        repoDir: File = File(System.getProperty("user.dir")),
    ): ReapSummary {
        require(export.isFile) { "Session export does not exist: $export" }
        require(selectedSessions.isNotEmpty()) { "At least one session ID is required" }

        val fileOps = JvmFileOperations()
        val casRoot = File(home, "cas")
        val manifest = File(home, ".oroboros/manifests/flywheel-history.tsv")
        casRoot.mkdirs()
        manifest.parentFile.mkdirs()
        val cas = FileCasStore(fileOps, casRoot.path)
        val receipts = mutableListOf<ExposureReceipt>()

        export.bufferedReader().useLines { lines ->
            for (record in lines) {
                val sessionId = exportedSessionId(record) ?: continue
                if (sessionId !in selectedSessions) continue

                val session = JsonSupport.parse(record) as? Map<*, *>
                    ?: error("Session $sessionId is not a JSON object")
                val messages = nodes(session["messages"])
                    .mapNotNull { it as? Map<*, *> }
                    .toList()
                val title = session["title"] as? String ?: sessionId
                val sourceMessage = messages.firstOrNull { it["role"] == "user" }
                    ?.get("id")
                    ?.let(::identityString)
                val observedAt = timestampMillis(session["started_at"])
                val searchableContent = messages.asSequence()
                    .mapNotNull { message ->
                        val content = message["content"] as? String ?: return@mapNotNull null
                        if (message["role"] == "user") meaningfulUserContent(content) else content
                    }
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
                val lexicalExcerpt = searchableContent.take(16_384)
                val transcriptCid = cas.put(record.encodeToByteArray())
                val paths = filePathPattern.findAll(searchableContent)
                    .map { it.value.trimEnd('.', ',', ':', ';', ')', ']', '}') }
                    .distinct()
                    .sorted()
                    .toList()
                val revisionCandidates = revisionPattern.findAll(searchableContent)
                    .map { it.value }
                    .distinct()
                    .sorted()
                    .toList()
                val revisions = verifiedGitRevisions(repoDir, revisionCandidates)
                val producers = producerPattern.findAll(searchableContent)
                    .map { it.value }
                    .distinct()
                    .sorted()
                    .toList()
                val tags = versionTagPattern.findAll(searchableContent)
                    .map { it.value.trimEnd('.', ',', ':', ';', ')', ']', '}') }
                    .distinct()
                    .sorted()
                    .toList()

                receipts += ExposureReceipt(
                    sourceSession = sessionId,
                    sourceMessage = sourceMessage,
                    observedAt = observedAt,
                    lexicalMemory = LexicalMemory(
                        summary = title,
                        title = title,
                        content = lexicalExcerpt,
                    ),
                    filePaths = paths.asSeries(),
                    artifactCid = transcriptCid,
                    gitRevisions = revisions.asSeries(),
                    producerRefs = producers.asSeries(),
                    versionTags = tags.asSeries(),
                )
            }
        }

        val found = receipts.mapTo(mutableSetOf()) { it.sourceSession }
        val missing = selectedSessions - found
        check(missing.isEmpty()) { "Sessions absent from export: ${missing.sorted().joinToString(",")}" }
        val sorted = receipts.sortedBy { it.sourceSession }
        writeManifest(manifest, sorted)
        return ReapSummary(sorted.asSeries(), manifest)
    }

    private fun writeManifest(manifest: File, receipts: List<ExposureReceipt>) {
        manifest.bufferedWriter().use { out ->
            out.appendLine(
                "session\tmessage\tobservedAt\tartifactCid\ttitle\tpaths\tgitRevisions\tproducerRefs\tversionTags\tterms"
            )
            for (receipt in receipts) {
                out.append(tsv(receipt.sourceSession)).append('\t')
                    .append(tsv(receipt.sourceMessage.orEmpty())).append('\t')
                    .append(receipt.observedAt.toString()).append('\t')
                    .append(receipt.artifactCid.value).append('\t')
                    .append(tsv(receipt.lexicalMemory.title)).append('\t')
                    .append(tsv(receipt.filePaths.joinValues())).append('\t')
                    .append(tsv(receipt.gitRevisions.joinValues())).append('\t')
                    .append(tsv(receipt.producerRefs.joinValues())).append('\t')
                    .append(tsv(receipt.versionTags.joinValues())).append('\t')
                    .append(tsv(receipt.lexicalMemory.terms.sorted().joinToString(",")))
                    .append('\n')
            }
        }
    }

    private fun exportedSessionId(record: String): String? {
        val marker = "\"id\": \""
        val start = record.indexOf(marker)
        if (start < 0) return null
        val valueStart = start + marker.length
        val end = record.indexOf('"', valueStart)
        return if (end > valueStart) record.substring(valueStart, end) else null
    }

    private fun meaningfulUserContent(content: String): String {
        val marker = "The user has provided the following instruction alongside the skill invocation:"
        return if (marker in content) content.substringAfterLast(marker) else content
    }

    private fun timestampMillis(value: Any?): Long {
        val numeric = (value as? Number)?.toDouble() ?: return 0L
        return if (numeric < 1_000_000_000_000.0) (numeric * 1_000.0).toLong() else numeric.toLong()
    }

    private fun identityString(value: Any): String = when (value) {
        is Byte, is Short, is Int, is Long -> value.toString()
        is Number -> value.toLong().toString()
        else -> value.toString()
    }

    /** Resolve candidate hashes as commits in one Git process; hex shape alone is not evidence. */
    private fun verifiedGitRevisions(repoDir: File, candidates: List<String>): List<String> {
        if (candidates.isEmpty()) return emptyList()
        val process = ProcessBuilder(
            "git", "-C", repoDir.path, "cat-file", "--batch-check=%(objectname) %(objecttype)"
        ).start()
        process.outputStream.bufferedWriter().use { input ->
            for (candidate in candidates) input.append(candidate).append('\n')
        }
        val results = process.inputStream.bufferedReader().readLines()
        val errors = process.errorStream.bufferedReader().readText()
        check(process.waitFor() == 0 && results.size == candidates.size) {
            "Unable to verify Git revisions in $repoDir: $errors"
        }
        return candidates.zip(results)
            .filter { (_, result) -> result.substringAfter(' ', "missing") == "commit" }
            .map { it.first }
    }

    private fun nodes(value: Any?): Sequence<Any?> = when (value) {
        is Array<*> -> value.asSequence()
        is Iterable<*> -> value.asSequence()
        else -> emptySequence()
    }

    private fun <T> List<T>.asSeries(): Series<T> = size j { index -> this[index] }

    private fun Series<String>.joinValues(): String = buildString {
        for (index in 0 until a) {
            if (index != 0) append(',')
            append(b(index))
        }
    }

    private fun tsv(value: String): String = value
        .replace('\t', ' ')
        .replace('\n', ' ')
        .replace('\r', ' ')

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size >= 2) {
            "Usage: reapFlywheelHistory -PhistoryExport=<jsonl> -PhistorySessions=<id,id,...>"
        }
        val export = File(args[0])
        val sessions = args.drop(1).toSet()
        val home = File(ForgeHome.defaultHome)
        val summary = reap(export, home, sessions)
        println("history-reap: receipts=${summary.receipts.a} manifest=${summary.manifest}")
        for (index in 0 until summary.receipts.a) {
            val receipt = summary.receipts.b(index)
            println("history-reap: session=${receipt.sourceSession} cid=${receipt.artifactCid.value}")
        }
    }
}
