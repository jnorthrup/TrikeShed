package borg.trikeshed.web.harness

import borg.trikeshed.web.harness.fflate.Unzip
import borg.trikeshed.web.harness.fflate.UnzipInflate
import borg.trikeshed.web.harness.fflate.strToU8
import borg.trikeshed.web.harness.fflate.unzipSync
import borg.trikeshed.web.harness.fflate.zipSync
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.set
import kotlin.js.Date

/** archive-core.js: the bounded ZIP adapter the decoder worker runs over fflate. */
object ArchiveCore {
    class Entry(val path: String, val mediaType: String, val bytes: Uint8Array)

    private class Streamed(val path: String, val mediaType: String) {
        val chunks = ArrayList<Uint8Array>()
        var length = 0
        var complete = false
    }

    private fun safeInteger(v: dynamic): Boolean = js("Number").isSafeInteger(v) as Boolean

    fun unpack(bytes: Uint8Array): List<Entry> {
        archiveCheck(bytes.length >= 22 && bytes.length <= ArchiveLimits.ZIP_BYTES, "ZIP must be 22 bytes to 4 MiB")
        val deadline = Date.now() + ArchiveLimits.ELAPSED_MS
        val metadata = LinkedHashMap<String, dynamic>()
        var declared = 0.0
        // Enumerate the central directory without inflating anything, including duplicate entries.
        val options: dynamic = jsObject()
        options.filter = { file: dynamic ->
            archiveCheck(Date.now() <= deadline, "ZIP decoding exceeded 10 seconds")
            val name = file.name as String
            archivePathParts(name)
            archiveCheck(!metadata.containsKey(name), "Duplicate archive path: $name")
            archiveCheck(metadata.size < ArchiveLimits.ENTRIES, "Archive entry limit is " + ArchiveLimits.ENTRIES)
            archiveCheck(file.compression == 0 || file.compression == 8, "Unsupported ZIP compression")
            val original = file.originalSize
            archiveCheck(safeInteger(original) && (original as Double) >= 0 && (original as Double) <= ArchiveLimits.FILE_BYTES, "Entry byte limit exceeded")
            declared += original as Double
            archiveCheck(declared <= ArchiveLimits.TOTAL_BYTES, "Archive expanded byte limit exceeded")
            archiveCheck(!name.endsWith("/") || original == 0, "Directory contains payload")
            metadata[name] = file
            false
        }
        unzipSync(bytes, options)
        archiveCheck(metadata.isNotEmpty(), "Archive is empty"); archiveCheckPaths(metadata.keys.toList())
        val entries = HashMap<String, Streamed>()
        var actual = 0
        // Streaming inflation measures actual output, not just untrusted ZIP size declarations.
        val reader = Unzip { file ->
            val name = file.name as String
            val meta: dynamic = metadata[name]
            archiveCheck(meta != null && !entries.containsKey(name), "ZIP headers disagree: $name")
            val entry = Streamed(name, archiveMediaType(name))
            entries[name] = entry
            val originalSize = (meta.originalSize as Number).toInt()
            file.ondata = { error: dynamic, chunk: Uint8Array, final: Boolean ->
                if (error != null) throw error.unsafeCast<Throwable>()
                actual += chunk.length; entry.length += chunk.length
                archiveCheck(actual <= ArchiveLimits.TOTAL_BYTES && entry.length <= ArchiveLimits.FILE_BYTES && entry.length <= originalSize, "ZIP expanded byte limit exceeded")
                entry.chunks.add(chunk)
                if (final) { archiveCheck(entry.length == originalSize, "ZIP entry size mismatch"); entry.complete = true }
            }
            file.start()
        }
        reader.register(UnzipInflate)
        var offset = 0
        while (offset < bytes.length) {
            archiveCheck(Date.now() <= deadline, "ZIP decoding exceeded 10 seconds")
            reader.push(bytes.subarray(offset, offset + 1024), offset + 1024 >= bytes.length)
            offset += 1024
        }
        archiveCheck(entries.size == metadata.size, "ZIP entries missing")
        return metadata.keys.map { path ->
            val entry = entries.getValue(path); archiveCheck(entry.complete, "Truncated ZIP entry: $path")
            val out = Uint8Array(entry.length)
            var at = 0
            for (chunk in entry.chunks) { out.set(chunk, at); at += chunk.length }
            Entry(path, entry.mediaType, out)
        }
    }

    private fun encode(bytes: Uint8Array): String {
        val text = StringBuilder()
        var i = 0
        while (i < bytes.length) {
            text.append(js("String.fromCharCode").apply(null, bytes.subarray(i, i + 8192)) as String)
            i += 8192
        }
        return js("btoa")(text.toString()) as String
    }

    /** The /api/archives/import request body. */
    fun request(entries: List<Entry>): dynamic {
        val body: dynamic = jsObject()
        body.entries = entries.map { e ->
            val o: dynamic = jsObject(); o.path = e.path; o.mediaType = e.mediaType; o.base64 = encode(e.bytes); o
        }.toTypedArray()
        return body
    }

    fun demo(): Uint8Array {
        val files: dynamic = jsObject()
        files["archive-demo/README.md"] = strToU8("# Archive specimen\n\nNested source, structured data, an empty directory, and binary bytes.\n")
        files["archive-demo/src/message.js"] = strToU8("export const message = \"browser and Node share archive-core.js\";\n")
        files["archive-demo/data/models.json"] = strToU8(jsonString(js("({models:[{id:'offline-fixture',status:'fixture',live:false}]})"), true))
        val binary = Uint8Array(256)
        for (i in 0 until 256) binary[i] = i.toByte()
        files["archive-demo/data/bytes.bin"] = binary
        files["archive-demo/empty/"] = Uint8Array(0)
        files["archive-demo/quoted \"name\".txt"] = strToU8("Filename escaping survives manifest storage and restore.\n")
        val options: dynamic = jsObject(); options.level = 6; options.mtime = Date(2020, 0, 1)
        return zipSync(files, options)
    }

    /** The program document a stored manifest mounts as (inspection-only). */
    fun projection(manifest: dynamic): dynamic {
        val cid = manifest.cid
        archiveCheck(jsTypeOf(cid) == "string" && isArchiveCid(cid as String), "Invalid archive CID")
        val list = manifest.entries
        archiveCheck(js("Array").isArray(list) as Boolean, "Invalid manifest entries")
        val entries = (list as Array<dynamic>).map { e ->
            archiveCheck(jsTypeOf(e.path) == "string", "Invalid archive path length")
            archiveCheck(js("Number").isInteger(e.ordinal) as Boolean && jsTypeOf(e.cid) == "string", "Invalid archive entry identity")
            ArchiveManifestEntry(e.path as String, (e.ordinal as Number).toDouble(), e.cid as String, if (e.mediaType == null) null else jsString(e.mediaType))
        }
        val root = archiveProjection(cid as String, entries)
        val doc: dynamic = jsObject()
        doc.nodes = arrayOf(node(root, true))
        doc.wires = arrayOf<dynamic>()
        doc.controls = js("({inspectionOnly:true,humanOversight:true})")
        doc.seq = 1
        return doc
    }

    private fun node(n: ArchiveProjectionNode, root: Boolean): dynamic {
        val o: dynamic = jsObject()
        o.id = n.id; o.type = n.type
        val params: dynamic = jsObject()
        for ((k, v) in n.params) params[k] = v
        if (n.type == "note") { o.x = 0; o.y = 0; o.params = params }
        else { o.params = params; o.children = n.children.map { node(it, false) }.toTypedArray(); o.x = 0; o.y = 0 }
        return o
    }

    /** window.ArchiveCore — shared name for the landscape and the worker. */
    fun install(target: dynamic) {
        val api: dynamic = jsObject()
        val limits: dynamic = jsObject()
        limits.zipBytes = ArchiveLimits.ZIP_BYTES; limits.entries = ArchiveLimits.ENTRIES; limits.fileBytes = ArchiveLimits.FILE_BYTES
        limits.totalBytes = ArchiveLimits.TOTAL_BYTES; limits.depth = ArchiveLimits.DEPTH; limits.path = ArchiveLimits.PATH; limits.elapsedMs = ArchiveLimits.ELAPSED_MS
        api.limits = js("Object").freeze(limits)
        api.directoryType = ARCHIVE_DIRECTORY_TYPE
        api.pathParts = { p: String -> archivePathParts(p).toTypedArray() }
        api.checkPaths = { p: Array<String> -> archiveCheckPaths(p.toList()) }
        api.unpack = { b: Uint8Array -> unpack(b).map { e -> val o: dynamic = jsObject(); o.path = e.path; o.mediaType = e.mediaType; o.bytes = e.bytes; o }.toTypedArray() }
        api.demo = { demo() }
        api.projection = { m: dynamic -> projection(m) }
        target.ArchiveCore = api
    }
}
