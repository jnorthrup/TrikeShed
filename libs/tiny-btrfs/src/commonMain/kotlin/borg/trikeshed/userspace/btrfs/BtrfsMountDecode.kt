package borg.trikeshed.userspace.btrfs

import borg.trikeshed.tinybtrfs.BtrfsMount
import borg.trikeshed.tinybtrfs.NodeId
import borg.trikeshed.tinybtrfs.readNodeImage

fun BtrfsMount.readLeaf(nodeId: NodeId): BtrfsLeaf {
    val bytes = readNodeImage(nodeId, verify = { decodeLeaf(it) }) ?: error("Node $nodeId not found")
    return decodeLeaf(bytes)
}

fun BtrfsMount.readInternal(nodeId: NodeId): BtrfsInternal {
    val bytes = readNodeImage(nodeId, verify = { decodeInternal(it) }) ?: error("Node $nodeId not found")
    return decodeInternal(bytes)
}
