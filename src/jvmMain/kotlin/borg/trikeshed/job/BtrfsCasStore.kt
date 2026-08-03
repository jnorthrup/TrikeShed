package borg.trikeshed.job

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.Series2
import borg.trikeshed.lib.j
import java.io.EOFException
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable

/**
 * BtrfsCasStore — CAS store backed by btrfs reflink deduplication.
 * 
 * Design:
 * - Each CID maps to a file in a flat directory: ${root}/${cid.value[0..1]}/${cid.value}
 * - On put(): write to temp file, then reflink to final location (btrfs COW)
 * - Identical content → identical CID → single physical extent (dedup)
 * - git tar import: stream tar entries, reflink into CAS, build manifest
 * 
 * Requires: btrfs filesystem with reflink support (Linux kernel 4.5+)
 * Fallback: copies if reflink fails (non-btrfs, cross-device, etc.)
 */
class BtrfsCasStore(
    private val root: File,
) {
    
    private val subdirs = mutableSetOf<String>()
    
    init {
        root.mkdirs()
    }
    
    private fun cidPath(cid: ContentId): File {
        val hash = cid.value
        val prefix = hash.substring(0, 2)
        val dir = File(root, prefix)
        if (!subdirs.contains(prefix)) {
            dir.mkdirs()
            subdirs.add(prefix)
        }
        return File(dir, hash)
    }
    
    suspend fun put(bytes: ByteArray): ContentId {
        val cid = ContentId.of(bytes)
        val target = cidPath(cid)
        
        if (target.exists()) {
            // Verify existing content matches (integrity check)
            val existing = target.readBytes()
            if (ContentId.of(existing) == cid) {
                return cid // Already stored, deduplicated
            }
            // Hash collision (extremely unlikely) or corruption
            throw IllegalStateException("CAS collision: $cid already exists with different content")
        }
        
        // Write to temp file first (atomic)
        val temp = withContext(Dispatchers.IO) { File.createTempFile("cas-", ".tmp", root) }
        try {
            withContext(Dispatchers.IO) {
                temp.writeBytes(bytes)
            }
            
            // Try reflink (btrfs COW deduplication)
            if (!reflink(temp, target)) {
                // Fallback: regular copy
                withContext(Dispatchers.IO) {
                    Files.copy(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                }
            }
        } finally {
            withContext(Dispatchers.IO + NonCancellable) {
                temp.delete()
            }
        }
        
        return cid
    }
    
    suspend fun get(cid: ContentId): ByteArray? {
        val target = cidPath(cid)
        if (!target.exists()) return null
        
        val bytes = target.readBytes()
        if (ContentId.of(bytes) != cid) {
            throw IllegalStateException("CAS integrity failure: stored blob for $cid does not match hash")
        }
        return bytes
    }
    
    /**
     * Import a git tar stream directly into CAS with reflink dedup.
     * Each tar entry becomes a CAS blob. Returns manifest of (path -> CID).
     */
    suspend fun importGitTar(tarStream: java.io.InputStream): Series2<String, ContentId> {
        val paths = mutableListOf<String>()
        val cids = mutableListOf<ContentId>()
        
        // Simple tar parser (POSIX ustar format)
        val buffer = ByteArray(512)
        while (true) {
            val read = tarStream.read(buffer)
            if (read != 512) break // End of archive
            
            val name = String(buffer, 0, 100).trim { it == '\u0000' }
            if (name.isEmpty()) break // End marker
            
            val sizeStr = String(buffer, 124, 12).trim()
            val size = sizeStr.toLongOrNull() ?: 0L
            
            if (size > 0) {
                // Read file content in 512-byte blocks
                val content = ByteArray(size.toInt())
                var offset = 0
                var remaining = size
                while (remaining > 0) {
                    val blockSize = minOf(512, remaining.toInt())
                    val blockRead = tarStream.read(content, offset, blockSize)
                    if (blockRead <= 0) throw EOFException("Unexpected EOF in tar")
                    offset += blockRead
                    remaining -= blockRead
                }
                // Skip padding to next 512-byte boundary
                val padding = (512 - (size % 512)) % 512
                if (padding > 0) tarStream.skip(padding.toLong())
                
                // Store in CAS with dedup
                val cid = put(content)
                paths.add(name)
                cids.add(cid)
            }
        }
        
        return paths.size j { i -> paths[i] j cids[i] }
    }
    
    /**
     * Export CAS blobs as a tar stream (for git fast-import or backup).
     */
    suspend fun exportTar(manifest: Series2<String, ContentId>, output: java.io.OutputStream) {
        val n = manifest.a
        for (i in 0 until n) {
            val entry = manifest.b(i)
            val path = entry.a
            val cid = entry.b
            
            // Explicit error on missing/corrupt CAS blob
            val content = get(cid) ?: throw IllegalStateException("Missing CAS blob for $cid")
            
            val headerBytes = ByteArray(512)
            
            val pathBytes = path.encodeToByteArray()
            require(pathBytes.size <= 100) { "Path too long for ustar: $path" }
            pathBytes.copyInto(headerBytes, 0)
            
            "0000644\u0000".encodeToByteArray().copyInto(headerBytes, 100)
            "0000000\u0000".encodeToByteArray().copyInto(headerBytes, 108)
            "0000000\u0000".encodeToByteArray().copyInto(headerBytes, 116)
            
            val sizeStr = content.size.toString(8).padStart(11, '0') + "\u0000"
            sizeStr.encodeToByteArray().copyInto(headerBytes, 124)
            
            "00000000000\u0000".encodeToByteArray().copyInto(headerBytes, 136)
            
            headerBytes[156] = '0'.code.toByte()
            
            "ustar\u0000".encodeToByteArray().copyInto(headerBytes, 257)
            "00".encodeToByteArray().copyInto(headerBytes, 263)
            
            "        ".encodeToByteArray().copyInto(headerBytes, 148)
            
            var checksum = 0
            for (b in headerBytes) {
                checksum += b.toInt() and 0xFF
            }
            
            val checksumStr = checksum.toString(8).padStart(6, '0') + "\u0000 "
            checksumStr.encodeToByteArray().copyInto(headerBytes, 148)
            
            output.write(headerBytes)
            output.write(content)
            
            val padding = (512 - (content.size % 512)) % 512
            if (padding > 0) {
                output.write(ByteArray(padding))
            }
        }
        
        // POSIX ustar requires two terminal zero blocks
        output.write(ByteArray(1024))
    }

    
    /**
     * btrfs reflink: clone range from src to dst (COW).
     * Returns true on success, false if not supported.
     */
    private suspend fun reflink(src: File, dst: File): Boolean {
        return try {
            val processOps = kotlin.coroutines.coroutineContext[borg.trikeshed.userspace.nio.channels.spi.ProcessOperations]
            if (processOps != null) {
                val result = processOps.exec(
                    command = "cp",
                    args = listOf("--reflink=always", "--", src.absolutePath, dst.absolutePath)
                )
                result.exitCode == 0
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Get physical disk usage (after dedup).
     * Uses `du -s --apparent-size` vs `du -s` to measure dedup ratio.
     */
    suspend fun diskUsage(): Pair<Long, Long> { // (apparent, physical)
<<<<<<< ours
        // ⚡ Bolt: Wrap blocking I/O operations in Dispatchers.IO to prevent coroutine starvation
        val apparent = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            Files.walk(root.toPath()).use { stream ->
                stream.filter { Files.isRegularFile(it) }
                    .mapToLong { Files.size(it) }
                    .sum()
            }
=======
        val apparent = Files.walk(root.toPath()).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .mapToLong { Files.size(it) }
                .sum()
>>>>>>> theirs
        }
        
        // Physical usage via `btrfs filesystem du` or `du -s`
        val physical = try {
            val processOps = kotlin.coroutines.coroutineContext[borg.trikeshed.userspace.nio.channels.spi.ProcessOperations]
            if (processOps != null) {
                val result = processOps.exec(
                    command = "du",
                    args = listOf("-s", "--block-size=1", "--", root.absolutePath)
                )
                if (result.exitCode == 0) {
                    result.stdout.decodeToString().split("\t").first().toLongOrNull() ?: apparent
                } else {
                    apparent
                }
            } else {
                apparent
            }
        } catch (e: Exception) {
            apparent
        }
        
        return Pair(apparent, physical)
    }
    
    /**
     * Deduplication ratio: apparent / physical.
     * 1.0 = no dedup, >1.0 = dedup savings.
     */
    suspend fun dedupRatio(): Double {
        val (apparent, physical) = diskUsage()
        return if (physical > 0) apparent.toDouble() / physical else 1.0
    }

    suspend fun sync() {
    }
}

/**
 * Git-style packfile index for CAS.
 * Maps object names to CIDs for fast lookup.
 */
class CasIndex(private val store: BtrfsCasStore) {
    private val index = mutableMapOf<String, ContentId>()
    
    fun put(name: String, cid: ContentId) {
        index[name] = cid
    }
    
    fun get(name: String): ContentId? = index[name]
    
    fun manifest(): Series2<String, ContentId> {
        val names = index.keys.toList()
        return names.size j { i -> names[i] j index[names[i]]!! }
    }
}