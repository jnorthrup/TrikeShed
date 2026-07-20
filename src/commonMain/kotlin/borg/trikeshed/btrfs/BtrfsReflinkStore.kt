package borg.trikeshed.btrfs

import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.userspace.nio.channels.spi.ProcessOperations
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.reflink.ReflinkScanner
import borg.trikeshed.reflink.ReferenceCounter

class BtrfsReflinkStore(
    private val rootDir: String,
    private val fileOps: FileOperations,
    private val processOps: ProcessOperations,
    private val scanner: ReflinkScanner,
    private val refCounter: ReferenceCounter
) : CasStore() {

    init {
        if (!fileOps.exists(rootDir)) {
            fileOps.mkdirs(rootDir)
        }
    }

    private fun cidPath(cid: ContentId): String {
        val hash = cid.value
        val prefix = hash.substring(0, 2)
        val dir = fileOps.resolvePath(rootDir, prefix)
        if (!fileOps.exists(dir)) {
            fileOps.mkdirs(dir)
        }
        return fileOps.resolvePath(dir, hash)
    }

    override fun put(bytes: ByteArray): ContentId {
        val cid = ContentId.of(bytes)
        val target = cidPath(cid)

        if (fileOps.exists(target)) {
            val existing = fileOps.readAllBytes(target)
            if (ContentId.of(existing) == cid) {
                refCounter.increment(cid)
                return cid
            }
            throw IllegalStateException("CAS collision: $cid already exists with different content")
        }

        // Perform deduplication scan
        val chunks = scanner.scan(bytes)
        
        // Write the new file
        fileOps.write(target, bytes)
        refCounter.increment(cid)

        // As an optimization/feature of btrfs reflink store, we could ideally reflink 
        // individual chunks here if they already exist, by invoking cp --reflink 
        // or a similar process. Since true btrfs dedupe often operates at the extent level,
        // we'd use processOps to trigger a dedupe tool, but for now we write the file
        // and let underlying tools handle it or we use cp --reflink if we copy files out.

        return cid
    }
    
    suspend fun reflinkCopy(srcCid: ContentId, dstPath: String): Boolean {
        val srcPath = cidPath(srcCid)
        if (!fileOps.exists(srcPath)) return false
        
        return try {
            val result = processOps.exec(
                command = "cp",
                args = listOf("--reflink=always", srcPath, dstPath)
            )
            result.exitCode == 0
        } catch (e: Exception) {
            false
        }
    }

    override fun get(cid: ContentId): ByteArray? {
        val target = cidPath(cid)
        if (!fileOps.exists(target)) return null

        val bytes = fileOps.readAllBytes(target)
        if (ContentId.of(bytes) != cid) {
            throw IllegalStateException("CAS integrity failure: stored blob for $cid does not match hash")
        }
        return bytes
    }
}
