package borg.trikeshed.web.harness

/** Bounds of the ZIP adapter shared by the page and its decoder worker (archive-core.js). */
object ArchiveLimits {
    const val ZIP_BYTES = 4 * 1024 * 1024
    const val ENTRIES = 128
    const val FILE_BYTES = 262144
    const val TOTAL_BYTES = 524288
    const val DEPTH = 12
    const val PATH = 512
    const val ELAPSED_MS = 10000
}

const val ARCHIVE_DIRECTORY_TYPE = "application/x-directory"

class ArchiveRefusal(message: String) : Exception(message)

fun archiveCheck(ok: Boolean, message: String) { if (!ok) throw ArchiveRefusal(message) }

private val CID = Regex("^sha256:[0-9a-f]{64}$")
private val UNSAFE = Regex("[\\x00-\\x1f\\x7f\\\\:]")

fun isArchiveCid(value: String?): Boolean = value != null && CID.matches(value)

fun archivePathParts(path: String): List<String> {
    archiveCheck(path.isNotEmpty() && path.length <= ArchiveLimits.PATH, "Invalid archive path length")
    val parts = path.removeSuffix("/").split("/")
    archiveCheck(parts.size <= ArchiveLimits.DEPTH && !UNSAFE.containsMatchIn(path) &&
        parts.all { it.isNotEmpty() && it != "." && it != ".." && it != "__proto__" }, "Unsafe archive path: $path")
    return parts
}

fun archiveCheckPaths(paths: List<String>) {
    val seen = HashSet<String>()
    val files = paths.filter { !it.endsWith("/") }.toSet()
    for (path in paths) {
        val parts = archivePathParts(path)
        val key = parts.joinToString("/")
        archiveCheck(key !in seen, "Duplicate archive path: $path"); seen.add(key)
        for (i in 1 until parts.size) archiveCheck(parts.subList(0, i).joinToString("/") !in files, "File/directory conflict: $path")
    }
}

private val MEDIA = mapOf(
    "txt" to "text/plain", "md" to "text/markdown", "json" to "application/json", "js" to "text/javascript", "cjs" to "text/javascript",
    "kt" to "text/plain", "html" to "text/html", "css" to "text/css", "csv" to "text/csv", "png" to "image/png", "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg", "gif" to "image/gif", "svg" to "image/svg+xml", "class" to "application/java-vm",
)

fun archiveMediaType(path: String): String {
    if (path.endsWith("/")) return ARCHIVE_DIRECTORY_TYPE
    return MEDIA[path.split(".").last().lowercase()] ?: "application/octet-stream"
}

/** One stored manifest entry, as /api/archives/manifest names it. */
data class ArchiveManifestEntry(val path: String, val ordinal: Double, val cid: String, val mediaType: String?)

/** A projected program node: `scope` for the archive and its directories, `note` for files. */
class ArchiveProjectionNode(val id: String, val type: String, val params: LinkedHashMap<String, String>) {
    val children = ArrayList<ArchiveProjectionNode>()
}

/** The inspection-only program a stored archive mounts as: directory scopes nesting file notes. */
fun archiveProjection(cid: String, entries: List<ArchiveManifestEntry>): ArchiveProjectionNode {
    archiveCheck(isArchiveCid(cid), "Invalid archive CID")
    archiveCheck(entries.isNotEmpty() && entries.size <= ArchiveLimits.ENTRIES, "Invalid manifest entries")
    archiveCheckPaths(entries.map { it.path })
    val root = ArchiveProjectionNode("archive", "scope", linkedMapOf("title" to "Archive " + cid.substring(7, 19), "archiveCid" to cid, "archivePath" to "/"))
    val directories = HashMap<String, ArchiveProjectionNode>().apply { put("", root) }
    fun directory(parts: List<String>): ArchiveProjectionNode {
        val key = parts.joinToString("/")
        directories[key]?.let { return it }
        val parent = directory(parts.dropLast(1))
        val node = ArchiveProjectionNode("directory:$key", "scope", linkedMapOf("title" to parts.last(), "archiveCid" to cid, "archivePath" to "$key/"))
        parent.children.add(node); directories[key] = node
        return node
    }
    for (entry in entries) {
        val parts = archivePathParts(entry.path)
        archiveCheck(entry.ordinal == kotlin.math.floor(entry.ordinal) && entry.ordinal >= 0 && isArchiveCid(entry.cid), "Invalid archive entry identity")
        if (entry.path.endsWith("/")) { directory(parts); continue }
        val ordinal = entry.ordinal.toLong().toString()
        directory(parts.dropLast(1)).children.add(ArchiveProjectionNode("entry:$ordinal", "note", linkedMapOf(
            "text" to parts.last(), "archiveCid" to cid, "archiveEntry" to ordinal, "archivePath" to entry.path, "contentCid" to entry.cid,
        ).also { p -> entry.mediaType?.let { p["mediaType"] = it } }))
    }
    return root
}

/** The label an archive-backed node shows in its title. */
fun archiveNodeTitle(archivePath: String): String =
    if (archivePath == "/") "Archive" else archivePath.removeSuffix("/").split("/").last()
