package borg.trikeshed.cas

import borg.trikeshed.collections.associative.LinearHashMap
import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.ConfixDoc
import borg.trikeshed.parse.confix.Syntax
import borg.trikeshed.parse.confix.confixDoc

/** Content identity joined to byte length. A null extent denotes a directory. */
typealias FileExtent = Join<ContentId, Long>

/** Canonical relative paths and extents; independent of mount, guest, and transfer format. */
class FileTreeManifest private constructor(val entries: Series2<String, FileExtent?>) {
    fun encode(): ByteArray = CanonicalCbor.encodeMap(mapOf(
        "format" to FORMAT,
        "entries" to entries.view.associate { (path, extent) ->
            path to if (extent == null) null else mapOf("contentId" to extent.a.value, "length" to extent.b)
        },
    ))

    fun document(): ConfixDoc = confixDoc(encode(), Syntax.CBOR)

    fun references(): Series<ContentId> = entries.view.mapNotNull { it.b?.a }
        .distinct().sortedBy { it.value }.toSeries()

    companion object {
        const val FORMAT = "file-tree-v1"
        const val CONTENT_TYPE = "application/vnd.trikeshed.file-tree+cbor"

        fun of(entries: Series2<String, FileExtent?>): FileTreeManifest {
            val ordered = entries.view.sortedBy { it.a }
            val directories = LinearHashMap<String, Boolean>()
            for ((path, extent) in ordered) {
                require(validPath(path) && path !in directories) { "Invalid or duplicate file-tree path: $path" }
                require(extent == null || extent.b in 0..Int.MAX_VALUE.toLong()) { "Invalid extent length: $path" }
                val parent = path.substringBeforeLast('/', "")
                require(parent.isEmpty() || directories[parent] == true) { "Missing directory: $parent" }
                directories[path] = extent == null
            }
            return FileTreeManifest(ordered.toSeries())
        }

        fun decode(bytes: ByteArray): FileTreeManifest {
            val fields = CanonicalCbor.decodeMap(bytes)
            require(fields["format"] == FORMAT) { "Unsupported file-tree format" }
            val rows = fields["entries"] as? Map<*, *> ?: error("Missing file-tree entries")
            val entries = rows.entries.map { (key, raw) ->
                val path = key as? String ?: error("Invalid file-tree path")
                val extent: FileExtent? = if (raw == null) null else {
                    val file = raw as? Map<*, *> ?: error("Invalid extent: $path")
                    val cid = ContentId(file["contentId"] as? String ?: error("Missing extent CID"))
                    val length = file["length"] as? Long ?: error("Invalid extent length")
                    cid j length
                }
                path j extent
            }.toSeries()
            val manifest = of(entries)
            require(manifest.encode().contentEquals(bytes)) { "Noncanonical file-tree manifest" }
            return manifest
        }

        internal fun validPath(path: String): Boolean = path.isNotEmpty() &&
            path.none { it == '\\' || it == '\u0000' || it == '\n' || it == '\r' || it == '\t' } &&
            path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }
    }
}
