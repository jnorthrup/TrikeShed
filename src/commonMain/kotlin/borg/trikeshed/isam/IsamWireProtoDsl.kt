@file:Suppress("unused")

package borg.trikeshed.isam

import borg.trikeshed.lib.*
import kotlinx.serialization.Serializable

/**
 * ISAM WireProto DSL — reified inline factory builders.
 *
 * FieldSynapse wire format (bit-packed LE):
 *   phase: Int     (phaseBits)    -- phase index
 *   opcode: UInt   (opcodeBits)   -- operation code
 *   methodIdx: Int (methodIdxBits)-- method index
 *   addr: Long     (addrBits)     -- instruction address
 *   seq: Long      (seqBits)      -- cursor index / sequence
 *   nano: Long     (nanoBits)     -- timestamp (nanoseconds)
 *   callsiteHash: UInt (hashBits) -- FNV-1a hash
 *   templateIdx: Int (templateBits)-- template index
 *
 * Total bits defined by WireProtoSpec.
 *
 * This DSL provides inline encode/decode with typeclass encoders.
 */

// ---------------------------------------------------------------------------
// Wire format spec
// ---------------------------------------------------------------------------

data class WireProtoSpec(
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
// FieldSynapse — encode/decode
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
    fun encode(spec: WireProtoSpec = STANDARD_WIRE_PROTO): ByteArray = encodeWireProtoInternal(spec, this)
    companion object {
        fun decode(bytes: ByteArray, spec: WireProtoSpec = STANDARD_WIRE_PROTO): FieldSynapse = decodeWireProtoInternal(spec, bytes)
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
    fun append(synapse: Synapse): Int = synchronizedLock(this) {
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

    companion object {
        fun <Synapse> create(capacity: Int = 4096): RingJournal<Synapse> =
            RingJournal(capacity, arrayOfNulls<Any?>(capacity) as Array<Synapse?>)
    }
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
// Encode/decode for hot path
// ---------------------------------------------------------------------------

fun encodeWireProto(
    synapse: FieldSynapse,
    spec: WireProtoSpec = STANDARD_WIRE_PROTO,
): ByteArray = encodeWireProtoInternal(spec, synapse)

fun decodeWireProto(
    bytes: ByteArray,
    spec: WireProtoSpec = STANDARD_WIRE_PROTO,
): FieldSynapse = decodeWireProtoInternal(spec, bytes)

// ---------------------------------------------------------------------------
// WireProtoSpec encode/decode implementation (internal for public inline access)
// ---------------------------------------------------------------------------

internal fun encodeWireProtoInternal(spec: WireProtoSpec, synapse: FieldSynapse): ByteArray {
    val buf = ByteArray(spec.totalBytes)
    var bitPos = 0
    fun writeBits(value: Long, bits: Int) {
        var v = value
        for (i in 0 until bits) {
            if ((v and 1L) != 0L) buf[bitPos / 8] = (buf[bitPos / 8].toInt() or (1 shl (bitPos % 8))).toByte()
            v = v ushr 1
            bitPos++
        }
    }
    writeBits(synapse.phase.toLong(), spec.phaseBits)
    writeBits(synapse.opcode.toLong(), spec.opcodeBits)
    writeBits(synapse.methodIdx.toLong(), spec.methodIdxBits)
    writeBits(synapse.addr, spec.addrBits)
    writeBits(synapse.seq, spec.seqBits)
    writeBits(synapse.nano, spec.nanoBits)
    writeBits(synapse.callsiteHash.toLong(), spec.hashBits)
    writeBits(synapse.templateIdx.toLong(), spec.templateBits)
    return buf
}

internal fun decodeWireProtoInternal(spec: WireProtoSpec, bytes: ByteArray): FieldSynapse {
    var bitPos = 0
    fun readBits(bits: Int): Long {
        var result = 0L
        for (i in 0 until bits) {
            val byteIdx = bitPos / 8
            val bitIdx = bitPos % 8
            if (byteIdx < bytes.size && (bytes[byteIdx].toInt() and (1 shl bitIdx)) != 0) {
                result = result or (1L shl i)
            }
            bitPos++
        }
        return result
    }
    return FieldSynapse(
        phase = readBits(spec.phaseBits).toInt(),
        opcode = readBits(spec.opcodeBits).toUInt(),
        methodIdx = readBits(spec.methodIdxBits).toInt(),
        addr = readBits(spec.addrBits),
        seq = readBits(spec.seqBits),
        nano = readBits(spec.nanoBits),
        callsiteHash = readBits(spec.hashBits).toUInt(),
        templateIdx = readBits(spec.templateBits).toInt(),
    )
}

// ---------------------------------------------------------------------------
// FNV-1a hash for callsite
// ---------------------------------------------------------------------------

fun fnv1aHash(data: String): UInt {
    var hash: UInt = 0x811c9dc5.toUInt()
    for (c in data.encodeToByteArray()) {
        hash = hash xor c.toUByte().toUInt()
        hash *= 0x01000193.toUInt()
    }
    return hash
}

// ---------------------------------------------------------------------------
// FieldSynapse builder
// ---------------------------------------------------------------------------

fun fieldSynapse(
    phase: Int,
    opcode: UInt,
    methodIdx: Int,
    addr: Long,
    seq: Long,
    nano: Long = kotlinx.datetime.Clock.System.now().toEpochMilliseconds() * 1_000_000,
    callsite: String = "",
    templateIdx: Int = 0,
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