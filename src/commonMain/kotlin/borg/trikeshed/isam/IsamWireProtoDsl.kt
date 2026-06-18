@file:Suppress("unused")

package borg.trikeshed.isam

import borg.trikeshed.lib.*
import kotlinx.serialization.Serializable

/**
 * ISAM WireProto DSL — reified inline factory builders.
 *
 * FieldSynapse wire format (24 bytes LE):
 *   phase: Int     (4 bytes)  -- phase index
 *   opcode: UInt   (4 bytes)  -- operation code
 *   methodIdx: Int (4 bytes)  -- method index
 *   addr: Long     (8 bytes)  -- instruction address
 *   seq: Long      (8 bytes)  -- cursor index / sequence
 *   nano: Long     (8 bytes)  -- timestamp (nanoseconds)
 *   callsiteHash: UInt (4 bytes) -- FNV-1a hash
 *   templateIdx: Int (4 bytes) -- template index
 *
 * Total: 44 bytes (traditional) or 24 bytes (compact)
 *
 * This DSL provides reified inline encode/decode with typeclass encoders.
 */

// ---------------------------------------------------------------------------
// Wire format spec
// ---------------------------------------------------------------------------

inline  class WireProtoSpec(
    val phaseBits: Int = 4,
    val opcodeBits: Int = 8,
    val methodIdxBits: Int = 4,
    val addrBits: Int = 8,
    val seqBits: Int = 8,
    val nanoBits: Int = 8,
    val hashBits: Int = 4,
    val templateBits: Int = 4,
) {
    val totalBits: Int = phaseBits + opcodeBits + methodIdxBits + addrBits + seqBits + nanoBits + hashBits + templateBits
    val totalBytes: Int = totalBits / 8
}

// Standard 24-byte LE format
val STANDARD_WIRE_PROTO = WireProtoSpec(phaseBits = 4, opcodeBits = 8, methodIdxBits = 4, addrBits = 8, seqBits = 8, nanoBits = 8, hashBits = 4, templateBits = 4)

// ---------------------------------------------------------------------------
// FieldSynapse — reified inline encode/decode
// ---------------------------------------------------------------------------

@Serializable
data class FieldSynapse(
    val phase: Int,
    val opcode: UInt,
    val methodIdx: Int,
    val addr: Long,
    val seq: Long,
    val nano: Long,
    val callsiteHash: UInt,
    val templateIdx: Int,
) {
    fun encode(spec: WireProtoSpec = STANDARD_WIRE_PROTO): ByteArray = spec.encode(this)
    companion object {
        fun decode(bytes: ByteArray, spec: WireProtoSpec = STANDARD_WIRE_PROTO): FieldSynapse = spec.decode(bytes)
    }
}

// ---------------------------------------------------------------------------
// RingSeries journal for hot path
// ---------------------------------------------------------------------------

/**
 * RingSeries<FieldSynapse> — lock-free append, bounded memory.
 * Used for ISAM pointcuts, Rete observability, dispatch events.
 */
class RingJournal<Synapse> private constructor(
    private val capacity: Int,
    private val buffer: Array<Synapse?>,
    private var head: Int = 0,
    private var size: Int = 0,
) {
    /** Atomic append — returns index of written slot. */
    fun append(synapse: Synapse): Int = synchronized(this) {
        val idx = head
        buffer[idx] = synapse
        head = (head + 1) % capacity
        if (size < capacity) size++
        idx
    }

    /** Snapshot as non-null Series (oldest first). */
    fun snapshot(): Series<Synapse> = size j { i ->
        val idx = (head - size + i + capacity) % capacity
        buffer[idx]!!
    }

    fun clear() { buffer.fill(null); head = 0; size = 0 }
    fun isFull(): Boolean = size == capacity
    fun isEmpty(): Boolean = size == 0
}

object RingJournal {
    inline fun <Synapse> create(crossinline capacity: Int = 4096): RingJournal<Synapse> =
        RingJournal(capacity, Array(capacity) { null })
}

// ---------------------------------------------------------------------------
// ReduxMutableSeries journal (capture -> checkpoint)
// ---------------------------------------------------------------------------

/**
 * Journal body for ReduxMutableSeries — ISAM wireproto format.
 * Used for checkpoint/restore of state machine.
 */
enum class JournalBody {
    /** Raw FieldSynapse bytes */
    RAW,
    /** ConfixDoc (Join<ConfixIndex, Series<Byte>>) */
    CONFIX,
    /** CBOR */
    CBOR,
}

// ---------------------------------------------------------------------------
// Reified inline encode/decode for hot path
// ---------------------------------------------------------------------------

inline fun encodeWireProto(
    crossinline synapse: FieldSynapse,
    crossinline spec: WireProtoSpec = STANDARD_WIRE_PROTO,
): ByteArray = spec.encode(synapse)

inline fun decodeWireProto(
    crossinline bytes: ByteArray,
    crossinline spec: WireProtoSpec = STANDARD_WIRE_PROTO,
): FieldSynapse = spec.decode(bytes)

// ---------------------------------------------------------------------------
// WireProtoSpec encode/decode implementation
// ---------------------------------------------------------------------------

private fun WireProtoSpec.encode(synapse: FieldSynapse): ByteArray {
    val buf = ByteArray(totalBytes)
    var bitPos = 0
    fun writeBits(value: Long, bits: Int) {
        var v = value
        for (i in 0 until bits) {
            if ((v and 1L) != 0L) buf[bitPos / 8] = (buf[bitPos / 8] or (1 shl (bitPos % 8))).toByte()
            v = v ushr 1
            bitPos++
        }
    }
    writeBits(synapse.phase.toLong(), phaseBits)
    val opcodeVal = synapse.opcode.toULong()
    writeBits(opcodeVal, opcodeBits)
    writeBits(synapse.methodIdx.toLong(), methodIdxBits)
    writeBits(synapse.addr, addrBits)
    writeBits(synapse.seq, seqBits)
    writeBits(synapse.nano, nanoBits)
    val hashVal = synapse.callsiteHash.toULong()
    writeBits(hashVal, hashBits)
    writeBits(synapse.templateIdx.toLong(), templateBits)
    return buf
}

private fun WireProtoSpec.decode(bytes: ByteArray): FieldSynapse {
    var bitPos = 0
    fun readBits(bits: Int): Long {
        var result = 0L
        for (i in 0 until bits) {
            val byteIdx = bitPos / 8
            val bitIdx = bitPos % 8
            if (byteIdx < bytes.size && (bytes[byteIdx] and (1 shl bitIdx)) != 0) {
                result = result or (1L shl i)
            }
            bitPos++
        }
        return result
    }
    return FieldSynapse(
        phase = readBits(phaseBits).toInt(),
        opcode = readBits(opcodeBits).toUInt(),
        methodIdx = readBits(methodIdxBits).toInt(),
        addr = readBits(addrBits),
        seq = readBits(seqBits),
        nano = readBits(nanoBits),
        callsiteHash = readBits(hashBits).toUInt(),
        templateIdx = readBits(templateBits).toInt(),
    )
}

// ---------------------------------------------------------------------------
// FNV-1a hash for callsite
// ---------------------------------------------------------------------------

inline fun fnv1aHash(data: String): UInt {
    var hash: UInt = 0x811c9dc5.toUInt()
    for (c in data.toByteArray()) {
        hash ^= c.toUInt()
        hash *= 0x01000193.toUInt()
    }
    return hash
}

// ---------------------------------------------------------------------------
// FieldSynapse builder (reified inline)
// ---------------------------------------------------------------------------

inline fun fieldSynapse(
    crossinline phase: Int,
    crossinline opcode: UInt,
    crossinline methodIdx: Int,
    crossinline addr: Long,
    crossinline seq: Long,
    crossinline nano: Long = platformUtils.currentTimeMillis() * 1_000_000,
    crossinline callsite: String = "",
    crossinline templateIdx: Int = 0,
): FieldSynapse = FieldSynapse(
    phase = phase,
    opcode = opcode,
    methodIdx = methodIdx,
    addr = addr,
    seq = seq,
    nano = nano,
    callsiteHash = fnv1aHash(callsite),
    templateIdx = templateIdx,
)

// ---------------------------------------------------------------------------
// Opcode constants
// ---------------------------------------------------------------------------

enum class Opcode(val byte: UInt) {
    // Dispatch
    SPAWN_AGENT(0x01.toUInt()),
    RECLAIM_LEASE(0x02.toUInt()),
    PROMOTE_CARD(0x03.toUInt()),
    BACKOFF_KEY(0x04.toUInt()),
    COMPLETE_CARD(0x05.toUInt()),
    BLOCK_CARD(0x06.toUInt()),

    // ISAM
    META_APPEND(0x10.toUInt()),
    DATA_APPEND(0x11.toUInt()),
    CHECKPOINT(0x20.toUInt()),
    RESTORE(0x21.toUInt()),

    // CRMS
    EIGEN_START(0x30.toUInt()),
    EIGEN_STEP(0x31.toUInt()),
    QUORUM_VOTE(0x32.toUInt()),

    // System
    HEARTBEAT(0xFF.toUInt()),
}

// ---------------------------------------------------------------------------
// Phase constants
// ---------------------------------------------------------------------------

enum class Phase(val int: Int) {
    INIT(0),
    DISPATCH(1),
    EXECUTE(2),
    COMPLETE(3),
    RECLAIM(4),
    ERROR(255),
}