package borg.trikeshed.tinybtrfs

import java.io.File
import java.util.UUID

/**
 * FileDiskAdapter: simple file-backed DiskAdapter for JVM platforms.
 *
 * Nodes are stored as files under the provided directory using the nodeId as filename.
 * This is a simple, portable backing implementation suitable for tests and local use.
 */
class FileDiskAdapter(private val dir: File) : DiskAdapter {
    init { if (!dir.exists()) dir.mkdirs() }

    private fun fileFor(nodeId: String) = File(dir, nodeId)

    override fun readNode(nodeId: String): ByteArray? {
        val f = fileFor(nodeId)
        return if (f.exists()) f.readBytes() else null
    }

    override fun writeNode(nodeId: String, bytes: ByteArray) {
        val f = fileFor(nodeId)
        f.parentFile?.mkdirs()
        f.writeBytes(bytes)
    }

    override fun allocateNode(): String {
        val id = UUID.randomUUID().toString()
        val f = fileFor(id)
        f.parentFile?.mkdirs()
        f.writeBytes(ByteArray(0))
        return id
    }

    override fun freeNode(nodeId: String) {
        fileFor(nodeId).delete()
    }
}
