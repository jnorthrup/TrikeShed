package borg.trikeshed.couch.pijul

import borg.trikeshed.couch.htx.*

/**
 * Encode patches as compact couch deltas for transmission over HTX.
 *
 * A "couch delta" is a list of line-level edits with byte offsets and lengths,
 * suitable for streaming over the wire and applying with minimal memory.
 *
 * Encoding format (binary, big-endian integers):
 *   [u32: name_len][name bytes]
 *   [u8: hash_len][hash bytes]
 *   [u64: timestamp]
 *   [u32: num_depends][u8: dep_len][dep hash bytes] × num_depends
 *   [u32: num_edits][edit] × num_edits
 * Edit: [u8: op(0=ADD 1=DEL 2=MOV)][u32: start][u32: end][u32: text_len][text bytes]
 */

/** HTX block types for the codec. */
object PijulHtx {
    val PATCH_EOF: Byte = 0xC1.toByte()
    val DELTA_FRAME: Byte = 0xC2.toByte()
    val CONFLICT_MARKER: Byte = 0xC3.toByte()
}

/** A single line-level edit in a patch delta. */
data class DeltaLineEdit(
    val op: LineOperation,
    val lineStart: Int,
    val lineEnd: Int,
    val newText: String,
)

/** Encode a patch as a sequence of HtxBlockData.Data blocks (no HtxMessage overhead). */
object CouchDeltaCodec {

    /**
     * Encode a [Patch] as a sequence of HtxBlockData.Data blocks for transmission.
     * Returns a list of blocks batched at `batchSize` edits each.
     */
    fun encodePatch(patch: Patch, batchSize: Int = 64): List<HtxBlockData.Data> {
        val blocks = mutableListOf<HtxBlockData.Data>()

        // Header block
        blocks.add(HtxBlockData.Data(buildPatchHeader(patch)))

        // Edit batches
        val edits = extractEdits(patch)
        var offset = 0
        while (offset < edits.size) {
            val batch = edits.subList(offset, minOf(offset + batchSize, edits.size))
            blocks.add(HtxBlockData.Data(encodeDeltaBatch(batch)))
            offset += batch.size
        }

        // EOF marker
        val eofBytes = byteArrayOf(PijulHtx.PATCH_EOF) + patch.hash.bytes.copyOf()
        blocks.add(HtxBlockData.Data(eofBytes))
        return blocks
    }

    /**
     * Decode a sequence of HtxBlockData.Data blocks back into a [Patch].
     */
    fun decodePatch(blocks: List<HtxBlockData.Data>): Patch {
        if (blocks.isEmpty()) return createNullPatch()

        // First block is header
        val header = decodePatchHeader(blocks.first().bytes)
        val allEdits = mutableListOf<DeltaLineEdit>()

        for (block in blocks.drop(1)) {
            if (block.bytes.isEmpty()) continue
            if (block.bytes.first() == PijulHtx.PATCH_EOF) break
            allEdits.addAll(decodeDeltaBatch(block.bytes))
        }
        return buildPatch(header, allEdits)
    }

    /** Encode just the delta (edits only, no header) for bandwidth efficiency. */
    fun encodeDelta(edits: List<DeltaLineEdit>): ByteArray = encodeDeltaBatch(edits)

    /** Decode a delta batch from bytes. */
    fun decodeDelta(data: ByteArray): List<DeltaLineEdit> = decodeDeltaBatch(data)

    private fun encodeDeltaBatch(edits: List<DeltaLineEdit>): ByteArray {
        val ba = SimpleByteArrayOutput()
        ba.writeU32(edits.size)
        for (edit in edits) {
            ba.writeU8(edit.op.ordinal.toByte())
            ba.writeU32(edit.lineStart)
            ba.writeU32(edit.lineEnd)
            val textBytes = edit.newText.encodeToByteArray()
            ba.writeU32(textBytes.size)
            ba.write(textBytes)
        }
        return ba.toByteArray()
    }

    private fun decodeDeltaBatch(data: ByteArray): List<DeltaLineEdit> {
        val br = SimpleByteArrayInput(data)
        val n = br.readU32().toInt()
        val edits = mutableListOf<DeltaLineEdit>()
        repeat(n) {
            val op = LineOperation.entries[br.readU8().toInt()]
            val start = br.readU32().toInt()
            val end = br.readU32().toInt()
            val len = br.readU32().toInt()
            val textStart = br.pos
            val textEnd = textStart + len
            br.pos = textEnd
            val text = data.copyOfRange(textStart, textEnd).decodeToString()
            edits.add(DeltaLineEdit(op, start, end, text))
        }
        return edits
    }

    private fun buildPatchHeader(patch: Patch): ByteArray {
        val ba = SimpleByteArrayOutput()
        val nameBytes = patch.name.encodeToByteArray()
        ba.writeU32(nameBytes.size)
        ba.write(nameBytes)
        val hashLen = patch.hash.bytes.size.coerceIn(0, 255).toByte()
        ba.writeU8(hashLen)
        ba.write(patch.hash.bytes.copyOf())
        ba.writeU64(patch.timestamp)
        ba.writeU32(patch.dependsOn.size)
        for (dep in patch.dependsOn) {
            ba.writeU8(dep.bytes.size.coerceIn(0, 255).toByte())
            ba.write(dep.bytes.copyOf())
        }
        return ba.toByteArray()
    }

    private fun decodePatchHeader(data: ByteArray): DecodedPatchHeader {
        val br = SimpleByteArrayInput(data)
        val nameLen = br.readU32().toInt()
        val nameStart = br.pos
        br.pos = nameStart + nameLen
        val name = data.copyOfRange(nameStart, br.pos).decodeToString()
        val hashLen = br.readU8().toInt()
        val hashStart = br.pos
        br.pos = hashStart + hashLen
        val hash = PatchHash(data.copyOfRange(hashStart, br.pos))
        val timestamp = br.readU64()
        val nDeps = br.readU32().toInt()
        val deps = mutableSetOf<PatchHash>()
        repeat(nDeps) {
            val dLen = br.readU8().toInt()
            val dStart = br.pos
            br.pos = dStart + dLen
            deps.add(PatchHash(data.copyOfRange(dStart, br.pos)))
        }
        return DecodedPatchHeader(name, hash, timestamp, deps)
    }

    private data class DecodedPatchHeader(
        val name: String,
        val hash: PatchHash,
        val timestamp: Long,
        val dependsOn: Set<PatchHash>,
    )

    private fun extractEdits(patch: Patch): List<DeltaLineEdit> {
        // In a real implementation, this parses the patch's internal representation.
        // For now, return empty — the caller must supply edits directly.
        return emptyList()
    }

    private fun buildPatch(header: DecodedPatchHeader, edits: List<DeltaLineEdit>): Patch = object : Patch {
        override val name = header.name
        override val hash = header.hash
        override val timestamp = header.timestamp
        override val dependsOn = header.dependsOn
        override val isConflicted = false
        override fun invert() = this
        override fun apply(pristine: Pristine) = ApplyResult.Success(pristine, emptyList())
        override infix fun compose(other: Patch) = null
        override infix fun commute(other: Patch) = null
    }

    private fun createNullPatch(): Patch = object : Patch {
        override val name = "null"
        override val hash = PatchHash(byteArrayOf())
        override val timestamp = 0L
        override val dependsOn: Set<PatchHash> = emptySet()
        override val isConflicted = false
        override fun invert() = this
        override fun apply(pristine: Pristine) = ApplyResult.Success(pristine, emptyList())
        override infix fun compose(other: Patch) = other
        override infix fun commute(other: Patch) = null
    }
}

// --- Minimal binary I/O helpers (no BigInteger, no external deps) ---

private class SimpleByteArrayOutput {
    private val parts = mutableListOf<ByteArray>()
    private var size = 0

    fun write(b: ByteArray) { parts.add(b); size += b.size }
    fun writeU8(v: Byte) { parts.add(byteArrayOf(v)); size++ }
    fun writeU32(v: Int) {
        parts.add(byteArrayOf(
            (v ushr 24).toByte(),
            (v ushr 16).toByte(),
            (v ushr 8).toByte(),
            v.toByte(),
        )); size += 4
    }
    fun writeU64(v: Long) {
        parts.add(byteArrayOf(
            (v ushr 56).toByte(), (v ushr 48).toByte(), (v ushr 40).toByte(), (v ushr 32).toByte(),
            (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte(),
        )); size += 8
    }
    fun toByteArray(): ByteArray {
        val result = ByteArray(size)
        var pos = 0
        for (p in parts) { p.copyInto(result, pos); pos += p.size }
        return result
    }
}

private class SimpleByteArrayInput(private val data: ByteArray) {
    var pos = 0
    fun readU8(): Byte = data[pos++]
    fun readU32(): Long {
        return (((data[pos++].toInt() and 0xff) shl 24) or
                ((data[pos++].toInt() and 0xff) shl 16) or
                ((data[pos++].toInt() and 0xff) shl 8) or
                (data[pos++].toInt() and 0xff)).toLong()
    }
    fun readU64(): Long {
        return ((data[pos++].toLong() and 0xff) shl 56) or
                ((data[pos++].toLong() and 0xff) shl 48) or
                ((data[pos++].toLong() and 0xff) shl 40) or
                ((data[pos++].toLong() and 0xff) shl 32) or
                ((data[pos++].toLong() and 0xff) shl 24) or
                ((data[pos++].toLong() and 0xff) shl 16) or
                ((data[pos++].toLong() and 0xff) shl 8) or
                (data[pos++].toLong() and 0xff)
    }
}