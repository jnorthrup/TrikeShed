package borg.trikeshed.kanban

import borg.trikeshed.common.Files
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Files as LibFiles
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.userspace.nio.platform.spi.SystemOperations

/** Canonical persisted input. Derived cards, links, Rete facts, and causal nodes are not stored here. */
data class ForgeKanbanSource(
    val version: Int,
    val userId: String,
    val title: String,
    val sourcePath: String,
    val description: String,
    val contentId: String,
)

/**
 * Persists the original markdown as one JSON string description. Forge state is rebuilt by
 * [ForgeKanbanIngest] so the file cannot become a second mutable board truth.
 */
object ForgeBoardPersistence {
    private const val VERSION = 1

    fun baseDir(): String = "${SystemOperations.default.homedir}/.local/reactor/kanban"

    fun sourcePath(userId: String): String = "${baseDir()}/$userId.json"

    fun source(
        userId: String,
        markdown: String,
        markdownPath: String,
    ): ForgeKanbanSource = ForgeKanbanSource(
        version = VERSION,
        userId = userId,
        title = markdown.lineSequence()
            .firstOrNull { it.startsWith("TARGET:") }
            ?.removePrefix("TARGET:")
            ?.trim()
            ?: "Forge Kanban",
        sourcePath = markdownPath,
        description = markdown,
        contentId = ContentId.of(markdown.encodeToByteArray()).value,
    )

    fun persist(source: ForgeKanbanSource): Result<Unit> = runCatching {
        LibFiles.mkdirs(baseDir())
        Files.write(sourcePath(source.userId), encode(source))
    }

    fun load(userId: String): Result<ForgeKanbanSource> = runCatching {
        decode(Files.readString(sourcePath(userId)))
    }

    fun encode(source: ForgeKanbanSource): String = JsonSupport.stringify(
        linkedMapOf(
            "version" to source.version,
            "userId" to source.userId,
            "title" to source.title,
            "sourcePath" to source.sourcePath,
            "contentId" to source.contentId,
            "description" to source.description,
        )
    )

    fun decode(encoded: String): ForgeKanbanSource {
        val fields = JsonSupport.parse(encoded) as? Map<*, *> ?: error("source envelope is not an object")
        fun string(key: String): String =
            jsonUnescape(fields[key] as? String ?: error("missing $key"))

        val version = (fields["version"] as? Number)?.toInt()
            ?: error("missing version")
        require(version == VERSION) { "unsupported Forge Kanban source version: $version" }

        val source = ForgeKanbanSource(
            version = version,
            userId = string("userId"),
            title = string("title"),
            sourcePath = string("sourcePath"),
            description = string("description"),
            contentId = string("contentId"),
        )
        val actualContentId = ContentId.of(source.description.encodeToByteArray()).value
        require(actualContentId == source.contentId) {
            "Forge Kanban source contentId mismatch: expected=${source.contentId}, actual=$actualContentId, bytes=${source.description.encodeToByteArray().size}"
        }
        return source
    }

    private fun jsonUnescape(value: String): String = buildString(value.length) {
        var index = 0
        while (index < value.length) {
            val char = value[index++]
            if (char != '\\' || index >= value.length) {
                append(char)
                continue
            }
            when (val escaped = value[index++]) {
                '"' -> append('"')
                '\\' -> append('\\')
                '/' -> append('/')
                'b' -> append('\b')
                'f' -> append('\u000C')
                'n' -> append('\n')
                'r' -> append('\r')
                't' -> append('\t')
                'u' -> {
                    require(index + 4 <= value.length) { "truncated JSON unicode escape" }
                    append(value.substring(index, index + 4).toInt(16).toChar())
                    index += 4
                }
                else -> error("invalid JSON escape: \\$escaped")
            }
        }
    }
}