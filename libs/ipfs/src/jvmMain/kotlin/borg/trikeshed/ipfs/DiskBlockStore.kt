package borg.trikeshed.ipfs

import java.io.File
import java.nio.file.Files

class DiskBlockStore(private val baseDir: File) : BlockStore {
    init {
        baseDir.mkdirs()
    }

    private fun nameFor(cid: CID): String = cid.bytes.joinToString("") { "%02x".format(it) }

    override suspend fun put(cid: CID, data: ByteArray) {
        val f = File(baseDir, nameFor(cid))
        Files.write(f.toPath(), data)
    }

    override suspend fun get(cid: CID): ByteArray? {
        val f = File(baseDir, nameFor(cid))
        return if (f.exists()) Files.readAllBytes(f.toPath()) else null
    }
}
