package borg.trikeshed.job

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.Series2
import borg.trikeshed.lib.j
import java.io.EOFException
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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
) : CasStore() {
    
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
    
    override fun put(bytes: ByteArray): ContentId {
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
        val temp = File.createTempFile("cas-", ".tmp", root)
        try {
            temp.writeBytes(bytes)
            
            // Try reflink (btrfs COW deduplication)
            if (!reflink(temp, target)) {
                // Fallback: regular copy
                Files.copy(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            }
        } finally {
            temp.delete()
        }
        
        return cid
    }
    
    override fun get(cid: ContentId): ByteArray? {
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
    fun importGitTar(tarStream: java.io.InputStream): Series2<String, ContentId> {
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
    fun exportTar(paths: Series<String>, output: java.io.OutputStream) {
        // Implementation for exporting
        TODO("exportTar")
    }
    
    /**
     * btrfs reflink: clone range from src to dst (COW).
     * Returns true on success, false if not supported.
     */
    private fun reflink(src: File, dst: File): Boolean {
        return try {
            // Linux: ioctl FICLONE / FICLONERANGE
            // Use JNI or shell out to cp --reflink
            val result = ProcessBuilder("cp", "--reflink=always", src.absolutePath, dst.absolutePath)
                .start()
                .waitFor()
            result == 0
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Get physical disk usage (after dedup).
     * Uses `du -s --apparent-size` vs `du -s` to measure dedup ratio.
     */
    fun diskUsage(): Pair<Long, Long> { // (apparent, physical)
        val apparent = Files.walk(root.toPath())
            .filter { Files.isRegularFile(it) }
            .mapToLong { Files.size(it) }
            .sum()
        
        // Physical usage via `btrfs filesystem du` or `du -s`
        val physical = try {
            ProcessBuilder("du", "-s", "--block-size=1", root.absolutePath)
                .start()
                .inputStream.readBytes()
                .decodeToString()
                .split("\t").first()
                .toLongOrNull() ?: apparent
        } catch (e: Exception) {
            apparent
        }
        
        return Pair(apparent, physical)
    }
    
    /**
     * Deduplication ratio: apparent / physical.
     * 1.0 = no dedup, >1.0 = dedup savings.
     */
    fun dedupRatio(): Double {
        val (apparent, physical) = diskUsage()
        return if (physical > 0) apparent.toDouble() / physical else 1.0
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