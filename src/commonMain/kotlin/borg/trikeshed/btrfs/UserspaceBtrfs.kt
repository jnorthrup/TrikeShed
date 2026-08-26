package borg.trikeshed.btrfs

import borg.trikeshed.job.ContentId
import borg.trikeshed.userspace.nio.file.spi.FileOperations

/**
 * MiniBtrfs — a userspace filesystem matching btrfs's three defining properties, not its on-disk
 * B-tree format:
 *
 *  - **Copy-on-write extents.** Content lands in an append-only, content-addressed extent store
 *    (SHA-256 via [ContentId], the same convention as the rest of TrikeShed's CAS). An extent is
 *    written once, under its own hash, and never mutated; writing back unchanged content is a
 *    dedup no-op, exactly as btrfs's own CoW never overwrites a live extent in place.
 *  - **Snapshots cheap in DATA, not files.** A subvolume's tree is a `path -> Entry` index; a
 *    snapshot copies that index (one small structure, sized by file COUNT), never the bytes behind
 *    it. Two subvolumes sharing every byte of a 2 GB tree cost the same to snapshot as two sharing
 *    nothing. This is the actual guarantee btrfs snapshots give — btrfs's own cost is O(tree nodes
 *    touched), not literally O(1) either — and nothing here claims more.
 *  - **Checksummed reads and a send/receive that fails closed.** Every extent fetch is verified
 *    against the hash it was stored under; [send] emits a manifest plus the extents it references,
 *    and [receive] verifies every extent against its declared hash BEFORE committing anything — a
 *    truncated or corrupted stream lands no partial subvolume, matching btrfs send/receive's own
 *    guarantee against a torn transfer.
 *
 * Unreferenced extents are reclaimed (mark-and-sweep across all live subvolumes) when a subvolume
 * is deleted — real btrfs defers this to a background cleaner; doing it eagerly here is the
 * honest choice for a toy with no cleaner thread, not a shortcut.
 *
 * One live [UserspaceBtrfs] instance is one mount: its index is loaded from [fileOps] once at
 * construction and kept in memory thereafter, exactly the assumption a live mounted filesystem
 * makes about the block device under it. Extent bytes and subvolume manifests are the only things
 * durable in [fileOps] — a second instance pointed at the same [rootDir] on the same backing store
 * sees prior state (manifests replay), never a *live* peer's uncommitted writes.
 */
class UserspaceBtrfs(val rootDir: String, val fileOps: FileOperations) {

    private data class Entry(val isDir: Boolean, val extentId: String?, val length: Long)

    private class Subvolume(var readOnly: Boolean) {
        var entries: Map<String, Entry> = emptyMap()
    }

    private val subvolumes = LinkedHashMap<String, Subvolume>()
    private val extentsDir get() = fileOps.resolvePath(rootDir, "extents")
    private val subvolDir get() = fileOps.resolvePath(rootDir, "subvolumes")

    init {
        if (!fileOps.exists(rootDir)) fileOps.mkdirs(rootDir)
        if (!fileOps.exists(extentsDir)) fileOps.mkdirs(extentsDir)
        if (!fileOps.exists(subvolDir)) fileOps.mkdirs(subvolDir)
        for (fileName in fileOps.listDir(subvolDir).map { it.substringBefore('/') }.distinct()) {
            val name = fileName.removeSuffix(".manifest")
            if (name == fileName || !isValidName(name)) continue // not a manifest file, or an invalid name
            runCatching { loadManifest(name) }
        }
    }

    // ── subvolumes ────────────────────────────────────────────────

    fun createSubvolume(name: String): Boolean {
        if (!isValidName(name) || subvolumes.containsKey(name)) return false
        subvolumes[name] = Subvolume(readOnly = false)
        persistManifest(name)
        return true
    }

    fun deleteSubvolume(name: String): Boolean {
        if (!isValidName(name)) return false
        subvolumes.remove(name) ?: return false
        runCatching { fileOps.deleteRecursively(manifestPathOf(name)) }
        sweepUnreferencedExtents()
        return true
    }

    fun hasSubvolume(name: String): Boolean = subvolumes.containsKey(name)

    fun listSubvolumes(): List<String> = subvolumes.keys.sorted()

    /** O(entries), never O(bytes): the index is copied, the extent store is shared by reference. */
    fun snapshot(sourceName: String, destName: String): Boolean {
        val src = subvolumes[sourceName] ?: return false
        if (!isValidName(destName) || subvolumes.containsKey(destName)) return false
        val dst = Subvolume(readOnly = true)
        dst.entries = src.entries // structural sharing: the NEXT write on either side copies, not this call
        subvolumes[destName] = dst
        persistManifest(destName)
        return true
    }

    // ── files ─────────────────────────────────────────────────────

    fun writeFile(subvol: String, file: String, content: ByteArray): Boolean {
        val sv = subvolumes[subvol] ?: return false
        if (sv.readOnly || !isValidFilePath(file)) return false
        val extentId = putExtent(content)
        val next = LinkedHashMap(sv.entries)
        ensureAncestors(next, file)
        next[file] = Entry(isDir = false, extentId = extentId, length = content.size.toLong())
        sv.entries = next // the copy-on-write: this subvolume's index moves forward; any snapshot's does not
        persistManifest(subvol)
        return true
    }

    fun fetchFile(subvol: String, file: String): ByteArray? {
        val sv = subvolumes[subvol] ?: return null
        val entry = sv.entries[file] ?: return null
        if (entry.isDir || entry.extentId == null) return null
        return getExtent(entry.extentId)
    }

    fun deleteFile(subvol: String, file: String): Boolean {
        val sv = subvolumes[subvol] ?: return false
        if (sv.readOnly || !isValidFilePath(file)) return false
        if (sv.entries[file]?.isDir != false) return false
        val next = LinkedHashMap(sv.entries)
        next.remove(file)
        sv.entries = next
        persistManifest(subvol)
        return true
    }

    // ── directories ───────────────────────────────────────────────

    fun createDirectory(subvol: String, directory: String): Boolean {
        val sv = subvolumes[subvol] ?: return false
        if (sv.readOnly) return false
        if (directory.isEmpty()) return true // root always exists
        if (!isValidFilePath(directory)) return false
        if (sv.entries[directory]?.isDir == true) return false // already exists
        val next = LinkedHashMap(sv.entries)
        ensureAncestors(next, "$directory/.")
        next[directory] = Entry(isDir = true, extentId = null, length = 0)
        sv.entries = next
        persistManifest(subvol)
        return true
    }

    fun listDirectory(subvol: String, directory: String = ""): List<String>? {
        val sv = subvolumes[subvol] ?: return null
        if (directory.isNotEmpty()) {
            if (!isValidFilePath(directory)) return null
            if (sv.entries[directory]?.isDir != true) return null
        }
        val prefix = if (directory.isEmpty()) "" else "$directory/"
        val result = LinkedHashSet<String>()
        for (key in sv.entries.keys) {
            if (key.startsWith(prefix) && key != directory) {
                val mapped = key.removePrefix(prefix).substringBefore('/')
                if (mapped.isNotEmpty()) result.add(mapped)
            }
        }
        val sortedList = result.toMutableList()
        sortedList.sort()
        return sortedList
    }

    fun isDirectory(subvol: String, path: String = ""): Boolean {
        if (path.isEmpty()) return subvolumes.containsKey(subvol)
        return subvolumes[subvol]?.entries?.get(path)?.isDir == true
    }

    fun isFile(subvol: String, path: String): Boolean =
        subvolumes[subvol]?.entries?.get(path)?.isDir == false

    // ── send / receive ────────────────────────────────────────────

    /** Deterministic, checksummed wire form: manifest lines, then each referenced extent once. */
    fun send(sourceName: String): ByteArray? {
        val sv = subvolumes[sourceName] ?: return null
        val lines = StringBuilder(MAGIC).append('\n')
        val hashes = LinkedHashSet<String>()
        for ((path, entry) in sv.entries.entries.sortedBy { it.key }) {
            if (entry.isDir) {
                lines.append("D\t").append(path).append('\n')
            } else {
                lines.append("F\t").append(path).append('\t').append(entry.extentId).append('\t').append(entry.length).append('\n')
                entry.extentId?.let(hashes::add)
            }
        }
        lines.append("END\n")
        val header = lines.toString().encodeToByteArray()
        val extentBlocks = ArrayList<ByteArray>(hashes.size * 2)
        var total = header.size
        for (hash in hashes) {
            val bytes = getExtent(hash) ?: return null // internal inconsistency: refuse to emit a lie
            val head = "EXT\t$hash\t${bytes.size}\n".encodeToByteArray()
            extentBlocks += head; extentBlocks += bytes
            total += head.size + bytes.size + 1 // +1 for the trailing newline
        }
        // A single pre-sized buffer, not a boxed ArrayList<Byte>: extent bytes can be large and
        // per-byte boxing would be exactly the naive-copy inefficiency this rewrite exists to avoid.
        val out = ByteArray(total)
        var pos = 0
        header.copyInto(out, pos); pos += header.size
        // extentBlocks alternates head/body; a trailing newline follows each body block only.
        var idx = 0
        while (idx < extentBlocks.size) {
            val head = extentBlocks[idx]; val body = extentBlocks[idx + 1]
            head.copyInto(out, pos); pos += head.size
            body.copyInto(out, pos); pos += body.size
            out[pos] = '\n'.code.toByte(); pos++
            idx += 2
        }
        return out
    }

    fun receive(destName: String, stream: ByteArray): Boolean {
        if (!isValidName(destName) || subvolumes.containsKey(destName)) return false
        return runCatching {
            val text = stream.decodeToString()
            if (!text.startsWith("$MAGIC\n")) return false
            val lines = text.split('\n')
            var i = 1
            data class Pending(val isDir: Boolean, val extentId: String?, val length: Long)
            val staged = LinkedHashMap<String, Pending>()
            while (i < lines.size && lines[i] != "END") {
                val cols = lines[i].split('\t')
                when (cols[0]) {
                    "D" -> staged[cols[1]] = Pending(true, null, 0)
                    "F" -> staged[cols[1]] = Pending(false, cols[2], cols[3].toLong())
                    else -> return false
                }
                i++
            }
            if (i >= lines.size) return false // no END: truncated
            i++ // past END
            // Extent blocks come as raw bytes with a text header; re-scan the ORIGINAL byte array
            // from the header's byte offset onward — text.split() already lost byte-exactness.
            var offset = indexOfLine(stream, "END\n") ?: return false
            offset += "END\n".encodeToByteArray() .size
            val stagedExtents = HashMap<String, ByteArray>()
            while (offset < stream.size) {
                val headerEnd = indexOfByte(stream, '\n'.code.toByte(), offset) ?: return false
                val header = stream.decodeToString(offset, headerEnd)
                val cols = header.split('\t')
                if (cols.size != 3 || cols[0] != "EXT") return false
                val hash = cols[1]
                val length = cols[2].toIntOrNull() ?: return false
                val bodyStart = headerEnd + 1
                val bodyEnd = bodyStart + length
                if (bodyEnd > stream.size) return false // truncated body: fail closed
                val bytes = stream.copyOfRange(bodyStart, bodyEnd)
                if (ContentId.of(bytes).value != hash) return false // corrupted: fail closed
                stagedExtents[hash] = bytes
                offset = bodyEnd + 1 // skip the trailing newline
            }
            // Every referenced hash must have arrived as a verified extent — no dangling references.
            for (p in staged.values) if (!p.isDir && (p.extentId == null || p.extentId !in stagedExtents)) return false
            // All-or-nothing: only now do we touch real state.
            for ((hash, bytes) in stagedExtents) putExtentVerified(hash, bytes)
            val sv = Subvolume(readOnly = true)
            sv.entries = staged.mapValues { (_, p) -> Entry(p.isDir, p.extentId, p.length) }
            subvolumes[destName] = sv
            persistManifest(destName)
            true
        }.getOrDefault(false)
    }

    // ── extent store (content-addressed, CoW) ───────────────────────

    private fun putExtent(bytes: ByteArray): String {
        val id = ContentId.of(bytes).value
        putExtentVerified(id, bytes)
        return id
    }

    private fun putExtentVerified(id: String, bytes: ByteArray) {
        val path = extentPathOf(id)
        if (!fileOps.exists(path)) fileOps.write(path, bytes.copyOf())
    }

    private fun getExtent(id: String): ByteArray? {
        val path = extentPathOf(id)
        if (!fileOps.exists(path)) return null
        val bytes = fileOps.readAllBytes(path)
        return if (ContentId.of(bytes).value == id) bytes else null // silent corruption caught, not served
    }

    private fun sweepUnreferencedExtents() {
        val live = HashSet<String>()
        for (sv in subvolumes.values) for (e in sv.entries.values) e.extentId?.let(live::add)
        for (name in fileOps.listDir(extentsDir).map { it.substringBefore('/') }.distinct()) {
            if (name !in live) runCatching { fileOps.deleteRecursively(extentPathOf(rawExtentName(name))) }
        }
    }

    // ── manifest persistence ─────────────────────────────────────

    private fun manifestPathOf(name: String) = fileOps.resolvePath(subvolDir, "$name.manifest")
    private fun extentPathOf(id: String) = fileOps.resolvePath(extentsDir, fileSafe(id))
    private fun fileSafe(id: String) = id.replace(':', '_')
    private fun rawExtentName(fileSafeName: String) = fileSafeName.replace('_', ':')

    private fun persistManifest(name: String) {
        val sv = subvolumes[name] ?: return
        val sb = StringBuilder(MAGIC).append('\n')
        sb.append(if (sv.readOnly) "RO\t1\n" else "RO\t0\n")
        for ((path, entry) in sv.entries.entries.sortedBy { it.key }) {
            sb.append(if (entry.isDir) "D\t$path\n" else "F\t$path\t${entry.extentId}\t${entry.length}\n")
        }
        fileOps.write(manifestPathOf(name), sb.toString().encodeToByteArray())
    }

    private fun loadManifest(name: String) {
        val text = fileOps.readAllBytes(manifestPathOf(name)).decodeToString()
        val lines = text.split('\n')
        if (lines.isEmpty() || lines[0] != MAGIC) return
        var readOnly = false
        val entries = LinkedHashMap<String, Entry>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isEmpty()) continue
            val cols = line.split('\t')
            when (cols[0]) {
                "RO" -> readOnly = cols.getOrNull(1) == "1"
                "D" -> entries[cols[1]] = Entry(true, null, 0)
                "F" -> entries[cols[1]] = Entry(false, cols.getOrNull(2), cols.getOrNull(3)?.toLongOrNull() ?: 0)
            }
        }
        val sv = Subvolume(readOnly)
        sv.entries = entries
        subvolumes[name] = sv
    }

    // ── path bookkeeping ──────────────────────────────────────────

    /** Every ancestor directory of [path] gets an explicit `D` entry — matches directories that
     *  actually contain files always being listable, without a separate "implicit dir" pass. */
    private fun ensureAncestors(entries: MutableMap<String, Entry>, path: String) {
        val parts = path.split('/').dropLast(1)
        var acc = ""
        for (p in parts) {
            acc = if (acc.isEmpty()) p else "$acc/$p"
            if (acc !in entries) entries[acc] = Entry(isDir = true, extentId = null, length = 0)
        }
    }

    private fun isValidName(name: String): Boolean {
        if (name.isEmpty() || name == "." || name == "..") return false
        if (name.contains("/") || name.contains("\\")) return false
        return true
    }

    private fun isValidFilePath(file: String): Boolean {
        if (file.isEmpty() || file == "." || file == "..") return false
        if (file.contains("..")) return false
        if (file.startsWith("/")) return false
        return true
    }

    private fun indexOfLine(haystack: ByteArray, needle: String): Int? {
        val n = needle.encodeToByteArray()
        outer@ for (i in 0..haystack.size - n.size) {
            for (j in n.indices) if (haystack[i + j] != n[j]) continue@outer
            return i
        }
        return null
    }

    private fun indexOfByte(haystack: ByteArray, byte: Byte, from: Int): Int? {
        for (i in from until haystack.size) if (haystack[i] == byte) return i
        return null
    }

    companion object {
        private const val MAGIC = "BTRFS_SEND_V2"
    }
}
