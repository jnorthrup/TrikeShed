package borg.trikeshed.tinybtrfs

import borg.trikeshed.lib.toSeries
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

   fun fileFor(nodeId: NodeId) = File(dir, nodeId.toString())

    override fun readNode(nodeId: NodeId): ByteArray? {
        val f = fileFor(nodeId)
        return if (f.exists()) f.readBytes() else null
    }

    override fun writeNode(nodeId: NodeId, bytes: ByteArray) {
        val f = fileFor(nodeId)
        f.parentFile?.mkdirs()
        f.writeBytes(bytes)
    }

    override fun allocateNode(): NodeId {
        val id = UUID.randomUUID().toString().toNodeId()
        val f = fileFor(id)
        f.parentFile?.mkdirs()
        f.writeBytes(ByteArray(0))
        return id
    }

    override fun freeNode(nodeId: NodeId) {
        fileFor(nodeId).delete()
    }
}
