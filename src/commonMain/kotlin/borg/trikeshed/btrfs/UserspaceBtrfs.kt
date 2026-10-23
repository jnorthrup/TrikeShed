package borg.trikeshed.btrfs

import borg.trikeshed.cas.FileExtent
import borg.trikeshed.cas.FileTreeManifest
import borg.trikeshed.cas.CasPaths
import borg.trikeshed.collections.associative.LinearHashMap
import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.util.oroboros.FileCasStore
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * File trees over an existing CAS and a filesystem publication boundary.
 * Canonical tree identity excludes the mount, subvolume name, and read-only flag.
 * Snapshots share immutable indexes and CAS extents; mutations publish a new
 * canonical tree before changing the live index. This is not the Btrfs disk format.
 *
 * A mount owns its live index. Separate mounts observe changes on reopen, not
 * through cache coherence. An injected CAS is shared and is never reclaimed here.
 * The default file CAS retains the earlier extent paths and local reclamation.
 */
class UserspaceBtrfs(
    val rootDir: String,
    val fileOps: FileOperations,
    cas: CasStore? = null,
    val metadataCompression: BtrfsMetadataCompression = BtrfsMetadataCompression.RAW,
    private val clock: () -> Instant = { Clock.System.now() },
) {
    private data class Entry(val extent: FileExtent?, val times: BtrfsFileTimes = BtrfsFileTimes.ZERO) {
        val isDir: Boolean get() = extent == null
    }
    private class Subvolume(val readOnly: Boolean, val entries: LinearHashMap<String, Entry>,
                            val times: BtrfsFileTimes = BtrfsFileTimes.ZERO)

    val cas: CasStore = cas ?: FileCasStore(fileOps, fileOps.resolvePath(rootDir, "extents"))
    private val ownsCas = cas == null
    private val subvolumes = LinearHashMap<String, Subvolume>()
    private val extentsDir get() = fileOps.resolvePath(rootDir, "extents")
    private val subvolDir get() = fileOps.resolvePath(rootDir, "subvolumes")
    private var publicationFailure: Throwable? = null

    init {
        if (!fileOps.exists(rootDir)) fileOps.mkdirs(rootDir)
        if (ownsCas && !fileOps.exists(extentsDir)) fileOps.mkdirs(extentsDir)
        if (!fileOps.exists(subvolDir)) fileOps.mkdirs(subvolDir)
        for (fileName in fileOps.listDir(subvolDir).sorted()) {
            if (!fileName.endsWith(".manifest")) continue
            val name = fileName.removeSuffix(".manifest")
            require(isValidName(name)) { "Invalid subvolume manifest name" }
            readManifest(fileOps.readAllBytes(manifestPathOf(name)))?.let { subvolumes[name] = it }
        }
    }

    fun createSubvolume(name: String): Boolean {
        operational()
        if (!isValidName(name) || name in subvolumes) return false
        publish(name, Subvolume(false, LinearHashMap(), BtrfsFileTimes.at(clock())))
        return true
    }

    fun deleteSubvolume(name: String): Boolean {
        operational()
        if (!isValidName(name) || name !in subvolumes) return false
        // A durable tombstone gives deletion the same publication boundary as updates.
        publishBytes(name, CanonicalCbor.encodeMap(mapOf("format" to LOCAL_FORMAT, "deleted" to true)))
        subvolumes.remove(name)
        if (ownsCas) runCatching { sweepUnreferencedExtents() }
        return true
    }

    fun hasSubvolume(name: String): Boolean { operational(); return name in subvolumes }

    fun listSubvolumes(): List<String> {
        operational()
        return subvolumes.entries().view.map { it.a }.sorted()
    }

    /** The index and extents are immutable after publication, so snapshots share both. */
    fun snapshot(sourceName: String, destName: String): Boolean {
        operational()
        val source = subvolumes[sourceName] ?: return false
        if (!isValidName(destName) || destName in subvolumes) return false
        publish(destName, Subvolume(true, source.entries, source.times))
        return true
    }

    fun writeFile(subvol: String, file: String, content: ByteArray): Boolean =
        writeFiles(subvol, s_[file j content])

    /** Validate the whole batch before storing extents, then publish one root. Last duplicate path wins. */
    fun writeFiles(subvol: String, files: Series<Join<String, ByteArray>>): Boolean {
        operational()
        val current = subvolumes[subvol] ?: return false
        if (current.readOnly) return false
        val content = LinearHashMap<String, ByteArray>()
        for ((file, bytes) in files.view) {
            if (!isValidPath(file) || current.entries[file]?.isDir == true) return false
            content[file] = bytes.copyOf()
        }
        if (content.count == 0) return true
        val entries = copyEntries(current.entries)
        val now = clock()
        var times = current.times
        for ((file, bytes) in content.entries().view) {
            if (entries[file]?.isDir == true || !ensureAncestors(entries, file, now)) return false
            val previous = entries[file]
            entries[file] = Entry(ContentId.of(bytes) j bytes.size.toLong(),
                previous?.times?.written(now) ?: BtrfsFileTimes.at(now))
            if (previous == null) touchParent(entries, file, now)
            if (file.substringBefore('/') !in current.entries) times = current.times.written(now)
        }
        for ((_, bytes) in content.entries().view) putVerified(bytes)
        publish(subvol, Subvolume(false, entries, times))
        return true
    }

    fun fetchFile(subvol: String, file: String): ByteArray? {
        operational()
        val extent = subvolumes[subvol]?.entries?.get(file)?.extent ?: return null
        return extentBytes(extent)
    }

    fun deleteFile(subvol: String, file: String): Boolean {
        operational()
        val current = subvolumes[subvol] ?: return false
        if (current.readOnly || !isValidPath(file) || current.entries[file]?.isDir != false) return false
        val entries = copyEntries(current.entries)
        entries.remove(file)
        val now = clock()
        touchParent(entries, file, now)
        publish(subvol, Subvolume(false, entries, if ('/' !in file) current.times.written(now) else current.times))
        return true
    }

    fun createDirectory(subvol: String, directory: String): Boolean {
        operational()
        val current = subvolumes[subvol] ?: return false
        if (current.readOnly) return false
        if (directory.isEmpty()) return true
        if (!isValidPath(directory) || directory in current.entries) return false
        val entries = copyEntries(current.entries)
        val now = clock()
        if (!ensureAncestors(entries, directory, now)) return false
        entries[directory] = Entry(null, BtrfsFileTimes.at(now))
        touchParent(entries, directory, now)
        publish(subvol, Subvolume(false, entries,
            if (directory.substringBefore('/') !in current.entries) current.times.written(now) else current.times))
        return true
    }

    fun times(subvol: String, path: String): BtrfsFileTimes? {
        operational()
        val current = subvolumes[subvol] ?: return null
        return if (path.isEmpty()) current.times else current.entries[path]?.times
    }

    /** Publish times through the same atomic local root as file bytes; snapshots remain immutable. */
    fun setTimes(subvol: String, path: String, lastModifiedTime: Instant? = null,
                 lastAccessTime: Instant? = null, creationTime: Instant? = null): Boolean {
        operational()
        val current = subvolumes[subvol] ?: return false
        if (current.readOnly) return false
        val previous = if (path.isEmpty()) current.times else current.entries[path]?.times ?: return false
        if (lastModifiedTime == null && lastAccessTime == null && creationTime == null) return true
        val times = previous.copy(lastModifiedTime = lastModifiedTime ?: previous.lastModifiedTime,
            lastAccessTime = lastAccessTime ?: previous.lastAccessTime,
            creationTime = creationTime ?: previous.creationTime, changeTime = clock())
        val entries = copyEntries(current.entries)
        if (path.isNotEmpty()) entries[path] = current.entries[path]!!.copy(times = times)
        publish(subvol, Subvolume(false, entries, if (path.isEmpty()) times else current.times))
        return true
    }

    fun listDirectory(subvol: String, directory: String = ""): List<String>? {
        operational()
        val current = subvolumes[subvol] ?: return null
        if (directory.isNotEmpty() && current.entries[directory]?.isDir != true) return null
        val prefix = if (directory.isEmpty()) "" else "$directory/"
        val names = mutableSetOf<String>()
        for ((path, _) in current.entries.entries().view) {
            if (path.startsWith(prefix) && path != directory) names.add(path.removePrefix(prefix).substringBefore('/'))
        }
        return names.sorted()
    }

    fun isDirectory(subvol: String, path: String = ""): Boolean {
        operational()
        return if (path.isEmpty()) subvol in subvolumes else subvolumes[subvol]?.entries?.get(path)?.isDir == true
    }

    fun isFile(subvol: String, path: String): Boolean {
        operational()
        return subvolumes[subvol]?.entries?.get(path)?.isDir == false
    }

    /** Canonical CAS/Confix tree, independent of physical storage and local metadata. */
    fun manifest(name: String): FileTreeManifest? {
        operational()
        return subvolumes[name]?.let(::tree)
    }

    fun manifestId(name: String): ContentId? = manifest(name)?.let { putVerified(it.encode()) }

    /** All referenced blobs must already be present and valid in this mount's CAS. */
    fun receiveManifest(destName: String, manifest: FileTreeManifest, readOnly: Boolean = true): Boolean {
        operational()
        if (!isValidName(destName) || destName in subvolumes) return false
        return runCatching {
            require(manifest.entries.view.all { isValidPath(it.a) }) { "Invalid file-tree path" }
            // Capture a canonical value before validating references or publishing it.
            val captured = FileTreeManifest.decode(manifest.encode())
            for ((_, extent) in captured.entries.view) if (extent != null) {
                requireNotNull(extentBytes(extent)) { "Missing or corrupt file-tree extent" }
            }
            publish(destName, Subvolume(readOnly, entries(captured)))
            true
        }.getOrDefault(false)
    }

    /** Legacy transfer writer retained for older peers. New replication uses [manifest]. */
    fun send(sourceName: String): ByteArray? {
        val manifest = manifest(sourceName) ?: return null
        val header = StringBuilder(MAGIC).append('\n')
        for ((path, extent) in manifest.entries.view) {
            if (extent == null) header.append("D\t$path\n")
            else header.append("F\t$path\t${extent.a.value}\t${extent.b}\n")
        }
        header.append("END\n")
        val blocks = mutableListOf<ByteArray>(header.toString().encodeToByteArray())
        var total = blocks[0].size.toLong()
        for (cid in manifest.references().view) {
            val bytes = blob(cid) ?: return null
            val line = "EXT\t${cid.value}\t${bytes.size}\n".encodeToByteArray()
            blocks.add(line); blocks.add(bytes); blocks.add(byteArrayOf(10))
            total += line.size.toLong() + bytes.size + 1
            require(total <= Int.MAX_VALUE) { "Legacy send stream exceeds byte-array capacity" }
        }
        // Length metadata is checked independently of the content hash.
        for ((_, extent) in manifest.entries.view) if (extent != null && extentBytes(extent) == null) return null
        val result = ByteArray(total.toInt())
        var offset = 0
        for (block in blocks) { block.copyInto(result, offset); offset += block.size }
        return result
    }

    /** Strict reader for earlier BTRFS_SEND_V2 streams, including raw binary extents. */
    fun receive(destName: String, stream: ByteArray): Boolean {
        operational()
        if (!isValidName(destName) || destName in subvolumes) return false
        return runCatching {
            var offset = 0
            fun line(): String {
                val end = (offset until stream.size).firstOrNull { stream[it] == 10.toByte() }
                    ?: error("Truncated transfer line")
                return stream.decodeToString(offset, end, throwOnInvalidSequence = true).also { offset = end + 1 }
            }
            require(line() == MAGIC)
            val staged = LinearHashMap<String, Entry>()
            while (true) {
                val row = line()
                if (row == "END") break
                parseEntry(staged, row)
            }
            val manifest = tree(Subvolume(true, staged))
            val received = LinearHashMap<ContentId, ByteArray>()
            while (offset < stream.size) {
                val fields = line().split('\t')
                require(fields.size == 3 && fields[0] == "EXT")
                val cid = ContentId(fields[1])
                val length = fields[2].toInt()
                require(length >= 0 && length < stream.size - offset && cid !in received)
                val bytes = stream.copyOfRange(offset, offset + length)
                require(stream[offset + length] == 10.toByte() && ContentId.of(bytes) == cid)
                received[cid] = bytes
                offset += length + 1
            }
            require(received.count == manifest.references().size) { "Unexpected or missing transfer extents" }
            for ((_, extent) in manifest.entries.view) if (extent != null) {
                require(received[extent.a]?.size?.toLong() == extent.b) { "Transfer extent length mismatch" }
            }
            for ((cid, bytes) in received.entries().view) require(putVerified(bytes) == cid)
            receiveManifest(destName, manifest)
        }.getOrDefault(false)
    }

    private fun tree(subvolume: Subvolume): FileTreeManifest =
        FileTreeManifest.of(subvolume.entries.entries() α { it.a j it.b.extent })

    private fun entries(manifest: FileTreeManifest): LinearHashMap<String, Entry> =
        LinearHashMap<String, Entry>().also { index ->
            for ((path, extent) in manifest.entries.view) index[path] = Entry(extent)
        }

    private fun copyEntries(source: LinearHashMap<String, Entry>): LinearHashMap<String, Entry> =
        LinearHashMap<String, Entry>().also { target ->
            for ((path, entry) in source.entries().view) target[path] = entry
        }

    private fun touchParent(index: LinearHashMap<String, Entry>, path: String, now: Instant) {
        val parent = path.substringBeforeLast('/', "")
        index[parent]?.let { index[parent] = it.copy(times = it.times.written(now)) }
    }

    private fun ensureAncestors(index: LinearHashMap<String, Entry>, path: String, now: Instant): Boolean {
        var slash = path.indexOf('/')
        while (slash >= 0) {
            val ancestor = path.substring(0, slash)
            val entry = index[ancestor]
            if (entry != null && !entry.isDir) return false
            if (entry == null) {
                index[ancestor] = Entry(null, BtrfsFileTimes.at(now))
                touchParent(index, ancestor, now)
            }
            slash = path.indexOf('/', slash + 1)
        }
        return true
    }

    private fun putVerified(bytes: ByteArray): ContentId {
        val expected = ContentId.of(bytes)
        if (blob(expected)?.contentEquals(bytes) == true) return expected
        require(cas.put(bytes) == expected) { "CAS returned an incorrect content identity" }
        require(blob(expected)?.contentEquals(bytes) == true) { "CAS publication verification failed" }
        return expected
    }

    private fun blob(cid: ContentId): ByteArray? = runCatching {
        // An error or wrong digest in the authoritative CAS is corruption, not
        // permission to fall back to another copy. Only absence permits migration.
        val present = cas.get(cid)
        if (present != null) return@runCatching present.takeIf { ContentId.of(it) == cid }?.copyOf()
        val paths = s_[CasPaths.blob(cid), CasPaths.legacyBlob(cid), cid.value.replace(':', '_')]
        for (relative in paths.view) {
            val path = fileOps.resolvePath(extentsDir, relative)
            if (!fileOps.isFile(path)) continue
            val bytes = fileOps.readAllBytes(path)
            if (ContentId.of(bytes) != cid) return@runCatching null
            if (!ownsCas) {
                require(cas.put(bytes) == cid) { "CAS migration identity mismatch" }
                require(cas.get(cid)?.contentEquals(bytes) == true) { "CAS migration verification failed" }
            }
            return@runCatching bytes.copyOf()
        }
        null
    }.getOrNull()

    private fun extentBytes(extent: FileExtent): ByteArray? = blob(extent.a)?.takeIf { it.size.toLong() == extent.b }

    private fun manifestPathOf(name: String) = fileOps.resolvePath(subvolDir, "$name.manifest")

    private fun publish(name: String, next: Subvolume) {
        val cid = putVerified(tree(next).encode())
        fun encoded(times: BtrfsFileTimes) = s_[times.lastModifiedTime, times.lastAccessTime, times.creationTime, times.changeTime]
            .view.flatMap { listOf(it.epochSeconds, it.nanosecondsOfSecond.toLong()) }
        val times = mutableMapOf<String, Any?>("" to encoded(next.times))
        for ((path, entry) in next.entries.entries().view) if (entry.times != BtrfsFileTimes.ZERO)
            times[path] = encoded(entry.times)
        publishBytes(name, CanonicalCbor.encodeMap(mapOf(
            "format" to LOCAL_FORMAT, "readOnly" to next.readOnly, "manifest" to cid.value, "times" to times,
        )))
        subvolumes[name] = next
    }

    private fun publishBytes(name: String, bytes: ByteArray) {
        val path = manifestPathOf(name)
        val stored = if (metadataCompression == BtrfsMetadataCompression.RAW) bytes
            else metadataCompression.encode(bytes)
        val before = if (fileOps.exists(path)) fileOps.readAllBytes(path).copyOf() else null
        try {
            fileOps.writeAtomically(path, stored)
            check(fileOps.readAllBytes(path).contentEquals(stored)) { "Manifest publication verification failed" }
        } catch (failure: Throwable) {
            val unchanged = runCatching {
                if (before == null) !fileOps.exists(path)
                else fileOps.exists(path) && fileOps.readAllBytes(path).contentEquals(before)
            }.getOrDefault(false)
            // A provider can fail after rename (for example on directory fsync).
            // Its mount must reopen rather than expose an index older than disk.
            if (!unchanged) publicationFailure = failure
            throw failure
        }
    }

    private fun readManifest(stored: ByteArray): Subvolume? {
        val bytes = BtrfsMetadataCompression.decode(stored)
        if (bytes.size >= MAGIC.length && bytes.decodeToString(0, MAGIC.length) == MAGIC) {
            val lines = bytes.decodeToString(throwOnInvalidSequence = true).split('\n')
            require(lines.size >= 3 && lines[0] == MAGIC && lines.last().isEmpty()) { "Invalid legacy manifest" }
            require(lines[1] == "RO\t0" || lines[1] == "RO\t1")
            val index = LinearHashMap<String, Entry>()
            for (row in lines.subList(2, lines.lastIndex)) parseEntry(index, row)
            return Subvolume(lines[1] == "RO\t1", entries(tree(Subvolume(false, index))))
        }
        val fields = CanonicalCbor.decodeMap(bytes)
        require(CanonicalCbor.encodeMap(fields).contentEquals(bytes) && fields["format"] in setOf(LOCAL_FORMAT, LEGACY_FORMAT)) {
            "Invalid subvolume manifest"
        }
        if (fields["deleted"] == true) {
            require(fields.size == 2)
            return null
        }
        val timed = fields["format"] == LOCAL_FORMAT
        require(fields.keys == if (timed) setOf("format", "readOnly", "manifest", "times") else setOf("format", "readOnly", "manifest"))
        require(fields["readOnly"] is Boolean && fields["manifest"] is String)
        val cid = ContentId(fields["manifest"] as String)
        val manifest = FileTreeManifest.decode(requireNotNull(blob(cid)) { "Missing or corrupt canonical manifest" })
        val index = entries(manifest)
        var rootTimes = BtrfsFileTimes.ZERO
        if (timed) {
            val times = fields["times"] as? Map<*, *> ?: error("Invalid inode times")
            require("" in times) { "Missing root inode times" }
            for ((path, value) in times) {
                require(path is String && (path.isEmpty() || path in index)) { "Inode times without an entry" }
                val row = value as? List<*> ?: error("Invalid inode times row")
                require(row.size == 8 && row.all { it is Long || it is Int }) { "Invalid inode times row" }
                fun instant(at: Int): Instant {
                    val seconds = (row[at] as Number).toLong()
                    val nanos = (row[at + 1] as Number).toInt()
                    require((row[at + 1] as Number).toLong() in 0..999_999_999L) { "Invalid nanoseconds" }
                    return Instant.fromEpochSeconds(seconds, nanos).also {
                        require(it.epochSeconds == seconds && it.nanosecondsOfSecond == nanos) { "Inode time overflow" }
                    }
                }
                val decoded = BtrfsFileTimes(instant(0), instant(2), instant(4), instant(6))
                if (path.isEmpty()) rootTimes = decoded else index[path] = index[path]!!.copy(times = decoded)
            }
        }
        return Subvolume(fields["readOnly"] as Boolean, index, rootTimes)
    }

    private fun parseEntry(index: LinearHashMap<String, Entry>, row: String) {
        val fields = row.split('\t')
        require(fields.size >= 2 && isValidPath(fields[1]) && fields[1] !in index)
        val extent: FileExtent? = when (fields[0]) {
            "D" -> { require(fields.size == 2); null }
            "F" -> {
                require(fields.size == 4)
                ContentId(fields[2]) j fields[3].toLong().also { require(it in 0..Int.MAX_VALUE.toLong()) }
            }
            else -> error("Unknown legacy manifest entry")
        }
        index[fields[1]] = Entry(extent)
    }

    private fun sweepUnreferencedExtents() {
        // Read all durable roots, including roots published by another mount.
        val live = mutableSetOf<ContentId>()
        for (fileName in fileOps.listDir(subvolDir)) if (fileName.endsWith(".manifest")) {
            val subvolume = readManifest(fileOps.readAllBytes(fileOps.resolvePath(subvolDir, fileName))) ?: continue
            val manifest = tree(subvolume)
            live.add(ContentId.of(manifest.encode()))
            for (cid in manifest.references().view) live.add(cid)
        }
        fun sweep(directory: String, prefix: String) {
            for (name in fileOps.listDir(directory)) {
                val path = fileOps.resolvePath(directory, name)
                val relative = prefix + name
                if (fileOps.isDir(path)) sweep(path, "$relative/")
                else if (fileOps.isFile(path)) {
                    val text = when {
                        SHARDED.matches(relative) || OLD_SHARDED.matches(relative) -> "sha256:" + relative.removePrefix("sha256/").replace("/", "")
                        FLAT.matches(relative) -> relative.replace('_', ':')
                        else -> continue
                    }
                    if (ContentId(text) !in live) fileOps.deleteRecursively(path)
                }
            }
        }
        sweep(extentsDir, "")
    }

    private fun operational() {
        check(publicationFailure == null) { "Manifest publication outcome is uncertain; reopen the mount" }
    }

    private fun isValidName(name: String): Boolean = isValidPath(name) && '/' !in name

    private fun isValidPath(path: String): Boolean = FileTreeManifest.validPath(path) &&
        path.encodeToByteArray().decodeToString() == path

    companion object {
        private const val MAGIC = "BTRFS_SEND_V2"
        private const val LEGACY_FORMAT = "userspace-subvolume-v1"
        private const val LOCAL_FORMAT = "userspace-subvolume-v2"
        private val SHARDED = Regex("sha256/(?:[0-9a-f]/){4}[0-9a-f]{60}")
        private val OLD_SHARDED = Regex("sha256/[0-9a-f]{2}/[0-9a-f]{62}")
        private val FLAT = Regex("sha256_[0-9a-f]{64}")
    }
}
