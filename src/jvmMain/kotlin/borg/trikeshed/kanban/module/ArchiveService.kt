package borg.trikeshed.kanban.module

import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.ReifiedSplitSeries2
import borg.trikeshed.cursor.`ColumnMeta↻`
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.litebike.JvmKanbanServer.HttpResponse
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.relaxfactory.CouchHttpSurface
import borg.trikeshed.treedoc.TreeDocK
import borg.trikeshed.treedoc.TreeDocPipeline
import borg.trikeshed.treedoc.TreeDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Bounded archive import/inspection. Paths are data, never filesystem destinations. */
@OptIn(ExperimentalEncodingApi::class)
internal class ArchiveService(cas: CasStore) {
    private val pipeline = TreeDocPipeline(cas, 65_536)
    private val admission = Mutex()

    suspend fun route(method: String, path: String, body: String): HttpResponse {
        val operation = path.substringBefore('?').substringAfterLast('/')
        if (method != if (operation == "import") "POST" else "GET") return json(405, "Method not allowed")
        if (!admission.tryLock()) return json(429, "Archive operation already in progress")
        try {
            return withContext(Dispatchers.IO) {
                if (operation == "import") {
                    if (body.length > MAX_BODY) return@withContext json(413, "Archive request byte limit exceeded")
                    val request = JsonSupport.parse(body) as? Map<*, *> ?: error("Expected archive object")
                    val raw = request["entries"] as? List<*> ?: error("Expected entries array")
                    require(raw.isNotEmpty() && raw.size <= MAX_ENTRIES) { "Archive entry limit is $MAX_ENTRIES" }
                    var total = 0
                    val documents = raw.map { value ->
                        val entry = value as? Map<*, *> ?: error("Invalid entry")
                        val name = entry["path"] as? String ?: error("Entry path required")
                        val directory = name.endsWith('/')
                        checkPath(name)
                        val encoded = entry["base64"] as? String ?: error("Entry bytes required")
                        require(encoded.length <= (MAX_FILE + 2) / 3 * 4) { "Entry byte limit exceeded" }
                        val bytes = Base64.decode(encoded)
                        require(bytes.size <= MAX_FILE && bytes.size <= MAX_TOTAL - total) { "Archive expanded byte limit exceeded" }
                        require(!directory || bytes.isEmpty()) { "Directory contains payload" }
                        total += bytes.size
                        val type = if (directory) DIRECTORY else entry["mediaType"] as? String ?: "application/octet-stream"
                        require(type.length <= 128 && type.none { it.code < 32 || it.code == 127 }) { "Invalid media type" }
                        TreeDocument(name, type, bytes)
                    }
                    checkPaths(documents.map { it.path })
                    // No writes before all names, sizes and encodings have passed validation.
                    val archive = pipeline.store(documents.toSeries())
                    response(201, manifest(archive))
                } else {
                    val query = CouchHttpSurface.parseQuery(path.substringAfter('?', ""))
                    val cid = ContentId(query["cid"] ?: error("Archive CID required"))
                    val archive = pipeline.open(cid, MAX_ENTRIES, MAX_ENTRIES + MAX_TOTAL / 65_536, 262_144)
                    if (operation == "manifest") response(200, manifest(archive))
                    else {
                        val ordinal = query["entry"]?.toIntOrNull() ?: error("Entry ordinal required")
                        val bytes = pipeline.restoreDocument(archive, ordinal, MAX_FILE)
                        HttpResponse(200, "", "application/octet-stream", bytes)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: NoSuchElementException) {
            return json(404, e.message ?: "Archive not found")
        } catch (e: IllegalArgumentException) {
            return json(400, e.message ?: "Invalid archive")
        } catch (e: IllegalStateException) {
            return json(422, e.message ?: "Archive cannot be restored")
        } finally {
            admission.unlock()
        }
    }

    @Suppress("UNCHECKED_CAST") // TreeDocPipeline owns the cursor schema.
    private fun manifest(archive: Series<Any?>): Map<String, Any?> {
        val docs = archive.b(TreeDocK.Documents.ordinal) as Cursor
        val entries = (0 until docs.size).map { ordinal ->
            val row = (docs.b(ordinal) as ReifiedSplitSeries2<Any?, `ColumnMeta↻`>).leftSeries
            mapOf("ordinal" to ordinal, "path" to row.b(0), "mediaType" to row.b(1), "cid" to (row.b(2) as ContentId).value)
        }
        checkPaths(entries.map { it["path"] as String })
        return mapOf("cid" to (archive.b(TreeDocK.ArchiveId.ordinal) as ContentId).value, "entries" to entries)
    }

    private fun checkPath(path: String) {
        val parts = path.removeSuffix("/").split('/')
        require(path.isNotEmpty() && path.length <= 512 && parts.size <= 12 &&
            path.none { it.code < 32 || it.code == 127 || it == '\\' || it == ':' } &&
            parts.none { it.isEmpty() || it == "." || it == ".." || it == "__proto__" }) { "Unsafe archive path: $path" }
    }

    private fun checkPaths(paths: List<String>) {
        val seen = mutableSetOf<String>()
        val files = paths.filterNot { it.endsWith('/') }.toSet()
        for (path in paths) {
            checkPath(path)
            require(seen.add(path.removeSuffix("/"))) { "Duplicate archive path: $path" }
            val parts = path.removeSuffix("/").split('/')
            for (depth in 1 until parts.size) require(parts.take(depth).joinToString("/") !in files) { "File/directory conflict: $path" }
        }
    }

    private fun response(status: Int, value: Any?) = HttpResponse(status, JsonSupport.stringify(value))
    private fun json(status: Int, error: String) = response(status, mapOf("error" to error))

    companion object {
        const val MAX_ENTRIES = 128
        const val MAX_FILE = 262_144
        const val MAX_TOTAL = 524_288
        const val MAX_BODY = 1_048_576
        const val DIRECTORY = "application/x-directory"
    }
}
