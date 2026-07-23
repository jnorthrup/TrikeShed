package borg.trikeshed.util.oroboros

import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.job.ContentId
import borg.trikeshed.userspace.nio.channels.spi.JvmProcessOperations
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

private data class OroborosOptions(
    val source: String,
    val home: String,
    val agent: String,
    val watch: Boolean,
    val intervalMillis: Long,
)

private data class IngestSummary(
    val files: Int,
    val bytes: Long,
    val sourceRevision: String,
    val forgeRevision: String,
)

fun main(args: Array<String>) = runBlocking {
    val options = parseOptions(args)
    val runtime = OroborosJvmRuntime(options)
    printSummary(runtime.ingest(), options, 0)
    if (!options.watch) return@runBlocking

    val watcher = JvmFileWatchReactorElement(
        options.source,
        coroutineContext[Job],
    )
    watcher.open()
    try {
        while (true) {
            val first = watcher.events.receiveCatching().getOrNull() ?: break
            val batch = mutableListOf(first)
            while (true) {
                val next = withTimeoutOrNull(options.intervalMillis) {
                    watcher.events.receiveCatching().getOrNull()
                } ?: break
                batch.add(next)
            }
            val summary = runtime.ingest()
            printSummary(summary, options, batch.size)
        }
    } finally {
        watcher.drain()
    }
}

private fun printSummary(summary: IngestSummary, options: OroborosOptions, changes: Int) {
    println(
        "oroboros: files=${summary.files} bytes=${summary.bytes} changes=$changes " +
            "source=${summary.sourceRevision} forge=${summary.forgeRevision} home=${options.home}"
    )
}

private class OroborosJvmRuntime(private val options: OroborosOptions) {
    private val fileOps = JvmFileOperations()
    private val processOps = JvmProcessOperations()
    private val projectRoot = ForgeHome.resolveSafe(
        options.home,
        "agents/${options.agent}/projects/trikeshed",
        fileOps,
    )
    private val manifestPath = ForgeHome.resolveSafe(
        options.home,
        ".oroboros/manifests/${options.agent}.tsv",
        fileOps,
        
    )
    private val casStore = FileCasStore(fileOps, fileOps.resolvePath(options.home, "cas"))
    private val attachments = CouchAttachmentGateway(CouchStoreFactory.inMemory(), casStore)
    private val version = GitVersionGateway(processOps)

    suspend fun ingest(): IngestSummary {
        provision()
        val paths = sourcePaths()
        val previous = readPreviousPaths()
        val current = paths.toSet()

        for (stale in previous - current) {
            val target = ForgeHome.resolveSafe(projectRoot, stale, fileOps)
            if (fileOps.exists(target)) fileOps.deleteRecursively(target)
        }

        val sourceRevision = git(options.source, "rev-parse", "HEAD").ifEmpty { "working-tree" }
        val manifest = StringBuilder("path\tcid\tlength\tcontentType\n")
        var totalBytes = 0L
        var ingestedFiles = 0

        for (relative in paths) {
            require('\t' !in relative && '\n' !in relative && '\r' !in relative) {
                "Unsupported control character in path: $relative"
            }
            val sourcePath = fileOps.resolvePath(options.source, relative)
            if (!fileOps.isFile(sourcePath)) continue
            val bytes = fileOps.readAllBytes(sourcePath)
            val cid = casStore.put(bytes)
            val target = ForgeHome.resolveSafe(projectRoot, relative, fileOps)
            parentOf(target)?.let { if (!fileOps.exists(it)) fileOps.mkdirs(it) }
            if (!sameContent(target, cid)) fileOps.write(target, bytes)

            val contentType = contentType(relative)
            attachments.putAttachment(
                OroborosAttachmentRef(
                    path = "projects/trikeshed/$relative",
                    contentType = contentType,
                    length = bytes.size.toLong(),
                    contentId = cid,
                    agentId = options.agent,
                    revision = sourceRevision,
                    sequence = 0L,
                ),
                bytes,
            )
            manifest.append(relative)
                .append('\t').append(cid.value)
                .append('\t').append(bytes.size)
                .append('\t').append(contentType)
                .append('\n')
            totalBytes += bytes.size
            ingestedFiles++
        }

        parentOf(manifestPath)?.let { if (!fileOps.exists(it)) fileOps.mkdirs(it) }
        fileOps.write(manifestPath, manifest.toString())

        if (!fileOps.isDir(fileOps.resolvePath(projectRoot, ".git"))) {
            check(version.init(projectRoot)) { "Unable to initialize Oroboros version store at $projectRoot" }
        }
        val forgeRevision = version.record(
            projectRoot,
            "oroboros <oroboros@localhost>",
            "Ingest TrikeShed at $sourceRevision",
        ) ?: error("Unable to record Oroboros project revision")

        fileOps.write(
            fileOps.resolvePath(options.home, ".oroboros", "status"),
            "agent=${options.agent}\nsource=${options.source}\nsourceRevision=$sourceRevision\n" +
                "forgeRevision=$forgeRevision\nfiles=$ingestedFiles\nbytes=$totalBytes\n",
        )

        return IngestSummary(ingestedFiles, totalBytes, sourceRevision, forgeRevision)
    }

    private fun provision() {
        for (path in listOf(options.home, projectRoot, fileOps.resolvePath(options.home, "cas"))) {
            if (!fileOps.exists(path)) fileOps.mkdirs(path)
        }
    }

    private suspend fun sourcePaths(): List<String> {
        val result = processOps.exec(
            "git",
            listOf("-C", options.source, "ls-files", "-z", "--cached", "--others", "--exclude-standard"),
        )
        check(result.exitCode == 0) {
            "Unable to enumerate TrikeShed files: ${result.stderr.decodeToString().trim()}"
        }
        return result.stdout.decodeToString()
            .split('\u0000')
            .filter { it.isNotEmpty() }
            .sorted()
    }

    private suspend fun git(home: String, vararg args: String): String {
        val result = processOps.exec("git", listOf("-C", home) + args)
        return if (result.exitCode == 0) result.stdout.decodeToString().trim() else ""
    }

    private fun readPreviousPaths(): Set<String> {
        if (!fileOps.exists(manifestPath)) return emptySet()
        return fileOps.readAllLines(manifestPath)
            .drop(1)
            .mapNotNull { it.substringBefore('\t').takeIf(String::isNotEmpty) }
            .toSet()
    }

    private fun sameContent(path: String, cid: ContentId): Boolean =
        fileOps.isFile(path) && ContentId.of(fileOps.readAllBytes(path)) == cid
}

private fun parseOptions(args: Array<String>): OroborosOptions {
    var source = JvmFileOperations().cwd()
    // Storage root is fixed: ForgeHome.defaultHome.
    // Override via OROBOROS_HOME env var (the documented deployment seam).
    // No --home CLI flag — silently allowing per-call roots scatters manifests
    // across $HOME/.local/forge_home, $HOME/.local/forge, etc.
    val home = ForgeHome.defaultHome
    var agent = "trikeshed"
    var watch = false
    var intervalMillis = 2_000L
    var index = 0
    while (index < args.size) {
        when (val arg = args[index]) {
            "--source" -> source = args.valueAfter(index++, arg)
            "--agent" -> agent = args.valueAfter(index++, arg)
            "--interval-ms" -> intervalMillis = args.valueAfter(index++, arg).toLong()
            "--watch" -> watch = true
            else -> error("Unknown Oroboros option: $arg (storage root is fixed at $home; set OROBOROS_HOME to override)")
        }
        index++
    }
    require(intervalMillis >= 250L) { "--interval-ms must be at least 250" }
    return OroborosOptions(source, home, agent, watch, intervalMillis)
}

private fun Array<String>.valueAfter(index: Int, option: String): String =
    getOrNull(index + 1) ?: error("Missing value after $option")

private fun parentOf(path: String): String? {
    val separator = maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\'))
    return if (separator > 0) path.substring(0, separator) else null
}

private fun contentType(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
    "kt", "kts" -> "text/kotlin"
    "md" -> "text/markdown"
    "json" -> "application/json"
    "yaml", "yml" -> "application/yaml"
    "toml" -> "application/toml"
    "html" -> "text/html"
    "css" -> "text/css"
    "js", "mjs" -> "application/javascript"
    "xml" -> "application/xml"
    "sh", "bash" -> "application/x-sh"
    "txt" -> "text/plain"
    "svg" -> "image/svg+xml"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "pdf" -> "application/pdf"
    "zip" -> "application/zip"
    "gz" -> "application/gzip"
    else -> "application/octet-stream"
}
