package borg.trikeshed.userspace.btrfs

import borg.trikeshed.context.ElementState
import borg.trikeshed.tinybtrfs.DiskAdapter

class BtrfsTreeRebuilder(
    private val diskAdapter: DiskAdapter,
) {
    data class RebuildResult(val nodeCount: Int)

    var state: ElementState = ElementState.CREATED
        private set

    fun beginRebuild() {
        check(state == ElementState.CREATED) { "beginRebuild() called in state $state (expected CREATED)" }
        state = ElementState.OPEN
    }

    fun completeRebuild(): RebuildResult {
        check(state == ElementState.OPEN) { "completeRebuild() called in state $state (expected OPEN)" }
        var nodeCount = 0
        var id = 1L
        while (true) {
            val candidateId = "n-$id"
            val bytes = diskAdapter.readNode(candidateId)
            if (bytes == null || bytes.isEmpty()) break
            if (bytes.size >= 4) { validateNode(bytes) }
            nodeCount++
            id++
        }
        state = ElementState.CLOSED
        return RebuildResult(nodeCount)
    }

    private fun validateNode(bytes: ByteArray) {
        // Generation overflow check: needs at least 8 bytes (generation at offset 8)
        if (bytes.size >= 8) {
            val generation = readU64LE(bytes, 8)
            if (generation == ULong.MAX_VALUE) throw IllegalArgumentException("Invalid generation: $generation")
        }
        // Magic check: needs at least 16 bytes for a full btrfs header
        if (bytes.size >= 16) {
            val magic = readU32LE(bytes, 0)
            if (magic != LEAF_MAGIC && magic != INTERNAL_MAGIC) {
                throw IllegalStateException("Invalid magic: 0x${magic.toString(16)}")
            }
        }
    }

    private fun readU32LE(data: ByteArray, pos: Int): UInt {
        var r = 0u
        r = r or (data[pos + 0].toUInt() and 0xFFu)
        r = r or ((data[pos + 1].toUInt() and 0xFFu) shl 8)
        r = r or ((data[pos + 2].toUInt() and 0xFFu) shl 16)
        r = r or ((data[pos + 3].toUInt() and 0xFFu) shl 24)
        return r
    }

    private fun readU64LE(data: ByteArray, pos: Int): ULong {
        var r = 0UL
        r = r or (data[pos + 0].toULong() and 0xFFUL)
        r = r or ((data[pos + 1].toULong() and 0xFFUL) shl 8)
        r = r or ((data[pos + 2].toULong() and 0xFFUL) shl 16)
        r = r or ((data[pos + 3].toULong() and 0xFFUL) shl 24)
        r = r or ((data[pos + 4].toULong() and 0xFFUL) shl 32)
        r = r or ((data[pos + 5].toULong() and 0xFFUL) shl 40)
        r = r or ((data[pos + 6].toULong() and 0xFFUL) shl 48)
        r = r or ((data[pos + 7].toULong() and 0xFFUL) shl 56)
        return r
    }

    companion object {
        const val LEAF_MAGIC = 0x464F5254u
        const val INTERNAL_MAGIC = 0x4E465242u
    }
}
