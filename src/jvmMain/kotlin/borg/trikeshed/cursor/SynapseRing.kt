package borg.trikeshed.cursor

import borg.trikeshed.lib.EvictionListener
import borg.trikeshed.lib.RingSeries

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Synaptic ring — RingSeries variant with timer-driven slab flush.
 *
 * Architecture:
 *   Producer: add() → RingSeries(capacity) [hot, zero-GC scratch buffer]
 *   Slab:     fire() or timeout → snapshot ring into immutable FieldSynapse[] array
 *   Handoff:  slab → SlabSubscriber.onSlab()
 *
 * Slab lifecycle:
 *   1. Producer writes into ring (O(1) add, zero allocation)
 *   2. When ring reaches capacity (fire) OR timeout ticks with events:
 *      - drain ring into FieldSynapse[] snapshot (one arraycopy)
 *      - ring.clear() — producer continues on fresh ring
 *      - snapshot handed to subscriber as immutable slab
 *   3. Subscriber gets each slab for CRMS fold / eigenvalue ranking
 *
 * Wireproto record (24 bytes, little-endian):
 *   offset  0: opcode       u8
 *   offset  1: phase        u8     — 0=BEFORE, 1=AFTER
 *   offset  2: methodIdx    u16    — InternPool index
 *   offset  4: addr         i32
 *   offset  8: seq          i32
 *   offset 12: nano         i64
 *   offset 20: callsiteHash u16
 *   offset 22: templateIdx  u16
 */
class SynapseRing(
    val capacity: Int = 2048,
) {
    init { require(capacity > 0 && (capacity and (capacity - 1)) == 0) { "capacity must be power of 2" } }

    // ── Ring (producer scratch buffer) ────────────────────────────────

    private val ring = RingSeries<FieldSynapse>(capacity, EvictionListener { })

    // ── Sequence ───────────────────────────────────────────────────────

    private var seq = 0
    fun nextSeq(): Int = seq++

    // ── Slab subscriber ───────────────────────────────────────────────

    interface SlabSubscriber {
        fun onSlab(slab: Array<out FieldSynapse>, count: Int, epoch: Long, nanoStart: Long, nanoEnd: Long)
    }

    var subscriber: SlabSubscriber? = null

    private var slabEpoch = 0L

    // ── Active gate ────────────────────────────────────────────────────

    var active: Boolean = false

    // ── Publish (hot path) ─────────────────────────────────────────────

    fun publish(evt: FieldSynapse) {
        if (!active) return
        ring.add(evt)
        if (ring.a == capacity) {
            flush("fire")
        }
    }

    // ── Slab flush ────────────────────────────────────────────────────

    fun flush(reason: String) {
        val count = ring.a
        if (count == 0) return

        @Suppress("UNCHECKED_CAST")
        val slab = arrayOfNulls<FieldSynapse>(count) as Array<FieldSynapse>
        var nanoStart = Long.MAX_VALUE
        var nanoEnd = Long.MIN_VALUE
        for (i in 0 until count) {
            val evt = ring.b(i)
            slab[i] = evt
            if (evt.nano < nanoStart) nanoStart = evt.nano
            if (evt.nano > nanoEnd)   nanoEnd = evt.nano
        }

        // clear ring
        while (ring.a > 0) { ring.removeAt(0) }

        val epoch = slabEpoch++
        subscriber?.onSlab(slab, count, epoch, nanoStart, nanoEnd)
    }

    fun timeoutFlush() {
        val count = ring.a
        if (count > 0 && count < capacity) {
            flush("timeout")
        }
    }

    // ── Query ─────────────────────────────────────────────────────────

    val size: Int get() = ring.a

    fun get(index: Int): FieldSynapse = ring.b(index)

    // ── Wireproto ─────────────────────────────────────────────────────

    val recordSize: Int get() = 24

    fun wireprotoLength(): Int = size * recordSize

    fun writeRecord(target: ByteBuffer, index: Int) {
        val evt = get(index)
        target.put(evt.opcode)
        target.put(evt.phase)
        target.putShort(evt.methodIdx.toShort())
        target.putInt(evt.addr)
        target.putInt(evt.seq)
        target.putLong(evt.nano)
        target.putShort(evt.callsiteHash.toShort())
        target.putShort(evt.templateIdx.toShort())
    }

    fun drainToWireproto(): ByteBuffer {
        val sz = size
        val buf = ByteBuffer.allocate(sz * recordSize).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until sz) {
            writeRecord(buf, i)
        }
        buf.flip()
        return buf
    }

    companion object {
        const val RECORD_SIZE = 24
    }
}