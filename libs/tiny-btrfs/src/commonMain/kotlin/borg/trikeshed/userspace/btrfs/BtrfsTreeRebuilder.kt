package borg.trikeshed.userspace.btrfs

import borg.trikeshed.context.ElementState
import borg.trikeshed.lib.*
import borg.trikeshed.tinybtrfs.*

data class BtrfsRebuildResult(
    val nodeCount: Int,
)

class BtrfsTreeRebuilder(
    private val buffer: UserspaceMemoryBuffer,
) {
    var state: ElementState = ElementState.CREATED
        private set

    fun beginRebuild() {
        check(state == ElementState.CREATED) { "Cannot begin rebuild from state=$state" }
        state = ElementState.OPEN
    }

    fun completeRebuild(): BtrfsRebuildResult {
        check(state == ElementState.OPEN) { "Cannot complete rebuild from state=$state" }
        val nodes = buffer.nodeSnapshot()
        nodes.forEach { (_, bytes) -> validateNode(bytes) }
        state = ElementState.CLOSED
        return BtrfsRebuildResult(nodeCount = nodes.size)
    }

    private fun validateNode(bytes: ByteArray) {
        if (bytes.isEmpty() || bytes.size < 4) return

        val magic = bytes.u32LeAt(0)
        check(magic == LEAF_MAGIC || magic == INTERNAL_MAGIC) {
            "Invalid btrfs node magic: 0x${magic.toString(16)}"
        }

        if (bytes.size >= 16) {
            val generation = bytes.u64LeAt(8)
            require(generation != ULong.MAX_VALUE) { "Invalid btrfs node generation: $generation" }
        }
    }
}

private const val LEAF_MAGIC: UInt = 0x464F5254u
private const val INTERNAL_MAGIC: UInt = 0x4E465242u

private fun ByteArray.u32LeAt(offset: Int): UInt =
    ((this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16) or
        ((this[offset + 3].toInt() and 0xFF) shl 24)).toUInt()

private fun ByteArray.u64LeAt(offset: Int): ULong {
    var value = 0UL
    for (index in 0 until 8) {
        value = value or ((this[offset + index].toULong() and 0xFFUL) shl (index * 8))
    }
    return value
}

data class BtrfsByteKey(
    val bytes: Series<Byte>,
) : Comparable<BtrfsByteKey> {
    override fun compareTo(other: BtrfsByteKey): Int {
        val limit = minOf(bytes.size, other.bytes.size)
        for (index in 0 until limit) {
            val left = bytes[index].toInt() and 0xFF
            val right = other.bytes[index].toInt() and 0xFF
            if (left != right) return left.compareTo(right)
        }
        return bytes.size.compareTo(other.bytes.size)
    }
}

/**
 * Rebuild a B+Tree from a set of key/value pairs.
 *
 * This is a helper to initialize a B+Tree without having to do individual insertions.
 *
 * @param diskAdapter The backing storage for nodes.
 * @param kvPairs     The key/value pairs to insert, sorted by key.
 */
@Suppress("UNUSED_PARAMETER")
fun BPlusTree<BtrfsByteKey, Series<Byte>>.rebuildFromSorted(
    diskAdapter: DiskAdapter,
    kvPairs: List<Pair<Series<Byte>, Series<Byte>>>,
) {
    // For simplicity, we'll just do individual inserts.
    // A more sophisticated bulk-loading algorithm could be implemented here.
    for ((key, value) in kvPairs) {
        put(BtrfsByteKey(key), value)
    }
}
