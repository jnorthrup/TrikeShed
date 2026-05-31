package borg.trikeshed.cursor

import borg.trikeshed.lib.RingSeries

/**
 * TypedefProductionSystem — CRMS data plane for typedef-adjacent call sites.
 *
 * Centralized rule algebra for typedef instrumentation:
 *   - AdjacentRule[] drives which bytecode sites get traced
 *   - algebra.eval(site) → instrument or skip
 *   - lattice depth (hops to terminal) captured per event
 *   - CRMS fold: groupBy(callsiteHash), pair BEFORE/AFTER at same site
 *   - eigsort by depth for conflict resolution priority
 *
 * Architecture:
 *   Producer: ClassfilePointcutRewriter → TypedefProductionSystem.publish()
 *             → RingSeries(2048) [hot, zero-GC scratch buffer]
 *   Slab:     fire() or timer tick → snapshot ring into immutable slab
 *   Handoff:  slab → SlabSubscriber.onSlab() for CRMS fold
 *
 * Wireproto record (24 bytes, little-endian):
 *   offset  0: opcode       u8     — CALL=0x10, ALLOC=0x11, RETURN=0x12, PROPERTY=0x13
 *   offset  1: phase        u8     — 0=BEFORE, 1=AFTER
 *   offset  2: typedefIdx   u16    — TypedefTable index of the typedef
 *   offset  4: methodIdx    i32    — InternPool index of method name
 *   offset  8: siteIdx      i32    — unique callsite within module
 *   offset 12: seq          i32    — monotonic sequence
 *   offset 16: nano         i64    — System.nanoTime() at publish
 *   offset 20: depth        u8     — typedef lattice depth (hops to terminal)
 *   offset 21: pad          u8
 *   offset 22: callsiteHash u16   — FNV-1a hash of (opcode, methodIdx, siteIdx)
 */
object TypedefProductionSystem {

    const val RING_CAP = 2048
    const val RECORD_SIZE = 24

    // ── Opcode constants ─────────────────────────────────────────────

    const val OP_CALL     = 0x10.toByte()
    const val OP_ALLOC    = 0x11.toByte()
    const val OP_RETURN   = 0x12.toByte()
    const val OP_PROPERTY = 0x13.toByte()
    const val OP_PARAMETER = 0x14.toByte()
    const val OP_CAST     = 0x15.toByte()

    // ── TypedefTable ─────────────────────────────────────────────────

    /**
     * Typedef registry — maps typedef name → canonical index + lattice depth.
     * Lazily populated. Wired to TypedefStructure at instrumentation time.
     */
    object TypedefTable {
        private val index = HashMap<String, Int>()
        private val table = arrayOfNulls<String>(65536)
        private val depths = ByteArray(65536)
        private var next = 0

        @Synchronized
        fun register(typedefName: String): Int =
            index.getOrPut(typedefName) {
                val idx = next++
                table[idx] = typedefName
                idx
            }

        fun resolve(idx: Int): String = table[idx] ?: ""
        fun depth(idx: Int): Byte = depths[idx]
        fun size(): Int = next

        // wire to TypedefStructure depth resolution at instrumentation time
        fun registerWithDepth(typedefName: String, depth: Byte): Int =
            index.getOrPut(typedefName) {
                val idx = next++
                table[idx] = typedefName
                depths[idx] = depth
                idx
            }
    }

    // ── InternPool ───────────────────────────────────────────────────

    object InternPool {
        private val table = arrayOfNulls<String>(65536)
        private val idxMap = HashMap<String, Int>()
        private var next = 0

        @Synchronized
        fun intern(s: String): Int =
            idxMap.getOrPut(s) {
                val idx = next++
                table[idx] = s
                idx
            }

        fun resolve(idx: Int): String = table[idx] ?: ""
        fun size(): Int = next
    }

    // ── Template strings ─────────────────────────────────────────────

    private val TPL_BEFORE_CALL   = InternPool.intern("BEFORE CALL %s.%s")
    private val TPL_AFTER_CALL    = InternPool.intern("AFTER  CALL %s.%s →")
    private val TPL_BEFORE_ALLOC  = InternPool.intern("BEFORE ALLOC %s")
    private val TPL_AFTER_ALLOC   = InternPool.intern("AFTER  ALLOC %s ←")
    private val TPL_BEFORE_RETURN = InternPool.intern("BEFORE RETURN %s.%s")
    private val TPL_AFTER_RETURN  = InternPool.intern("AFTER  RETURN %s.%s ←")
    private val TPL_BEFORE_PROP   = InternPool.intern("BEFORE PROP %s.%s : %s")
    private val TPL_AFTER_PROP    = InternPool.intern("AFTER  PROP %s.%s : %s →")

    // opcode name pool
    private val OP_CALL_NAME   = InternPool.intern("CALL")
    private val OP_ALLOC_NAME  = InternPool.intern("ALLOC")
    private val OP_RETURN_NAME  = InternPool.intern("RETURN")
    private val OP_PROP_NAME    = InternPool.intern("PROPERTY")
    private val OP_PARAM_NAME   = InternPool.intern("PARAMETER")
    private val OP_CAST_NAME    = InternPool.intern("CAST")

    // ── AdjacentRule algebra ─────────────────────────────────────────

    /**
     * Centralized rule for typedef adjacency decisions.
     * Drives which bytecode sites get instrumented.
     */
    data class AdjacentRule(
        val ownerPattern: String,   // "org/xvm/foo/**"
        val opcode: Byte,
        val phase: Byte,            // 0=BEFORE, 1=AFTER
        val typedefName: String,
        val depth: Byte,
        val methodFilter: String    // "get*" | "set*" | "*"
    )

    private val rules = ArrayList<AdjacentRule>()

    fun addRule(rule: AdjacentRule) { rules.add(rule) }

    /**
     * Algebra evaluation: does this site match any AdjacentRule?
     * @return true if site should be instrumented
     */
    fun eval(owner: String, opcode: Byte, methodName: String): AdjacentRule? {
        for (rule in rules) {
            if (matchesPattern(owner, rule.ownerPattern) &&
                rule.opcode == opcode &&
                matchesMethodFilter(methodName, rule.methodFilter)) {
                return rule
            }
        }
        return null
    }

    private fun matchesPattern(owner: String, pattern: String): Boolean {
        return if (pattern.endsWith("/**")) {
            val prefix = pattern.removeSuffix("/**").replace('.', '/')
            owner.startsWith(prefix)
        } else {
            owner.replace('.', '/') == pattern
        }
    }

    private fun matchesMethodFilter(method: String, filter: String): Boolean =
        filter == "*" || method.startsWith(filter.removeSuffix("*"))

    // ── Ring ─────────────────────────────────────────────────────────

    private val ring = RingSeries<TraceEvent>(RING_CAP) { }

    private var seq = 0
    private fun nextSeq(): Int = seq++

    @Volatile var active = false

    // ── Slab subscriber ─────────────────────────────────────────────

    interface SlabSubscriber {
        fun onSlab(slab: Array<TraceEvent>, count: Int, epoch: Long, nanoStart: Long, nanoEnd: Long)
    }

    @Volatile var subscriber: SlabSubscriber? = null
    private var slabEpoch = 0L

    // ── TraceEvent record ────────────────────────────────────────────

    /**
     * TraceEvent = RowVec = Series2<Any, () -> RecordMeta>
     * Named columns, lazy reification via reify().
     */
    data class TraceEvent(
        val opcode: Byte,
        val phase: Byte,
        val typedefIdx: Int,
        val methodIdx: Int,
        val siteIdx: Int,
        val seq: Int,
        val nano: Long,
        val depth: Byte,
        val callsiteHash: Int,
        val templateIdx: Int
    ) {
        fun opcodeName(): String = when (opcode.toInt() and 0xFF) {
            0x10 -> "CALL"; 0x11 -> "ALLOC"; 0x12 -> "RETURN"
            0x13 -> "PROPERTY"; 0x14 -> "PARAMETER"; 0x15 -> "CAST"
            else -> "OP_0x${Integer.toHexString(opcode.toInt() and 0xFF)}"
        }

        fun typedefName(): String = TypedefTable.resolve(typedefIdx)
        fun methodName(): String  = InternPool.resolve(methodIdx)
        fun phaseLabel(): String  = if (phase == 0.toByte()) "BEFORE" else "AFTER"

        /** Lazy reification — format string from pool, resolve on demand */
        fun reify(): String {
            val template = InternPool.resolve(templateIdx)
            return template
        }

        /** CRMS fold: match BEFORE + AFTER at same callsite */
        fun matches(other: TraceEvent): Boolean =
            other.callsiteHash == this.callsiteHash &&
            other.opcode == this.opcode &&
            other.phase != this.phase  // opposite phases
    }

    // ── Callsite hash ────────────────────────────────────────────────

    /**
     * FNV-1a 16-bit hash of (opcode, methodIdx, siteIdx).
     * Used for CRMS groupBy to pair BEFORE/AFTER at same site.
     */
    fun callsiteHash(opcode: Byte, methodIdx: Int, siteIdx: Int): Int {
        var h = 0x811c9dc5.toInt()
        h = ((h xor (opcode.toInt() and 0xFF)) * 0x01000193).toInt()
        h = ((h xor (methodIdx and 0xFF)) * 0x01000193).toInt()
        h = ((h xor ((methodIdx shr 8) and 0xFF)) * 0x01000193).toInt()
        h = ((h xor ((methodIdx shr 16) and 0xFF)) * 0x01000193).toInt()
        h = ((h xor ((methodIdx shr 24) and 0xFF)) * 0x01000193).toInt()
        h = ((h xor (siteIdx and 0xFF)) * 0x01000193).toInt()
        h = ((h xor ((siteIdx shr 8) and 0xFF)) * 0x01000193).toInt()
        h = ((h xor ((siteIdx shr 16) and 0xFF)) * 0x01000193).toInt()
        h = ((h xor ((siteIdx shr 24) and 0xFF)) * 0x01000193).toInt()
        return h
    }

    private fun templateIdx(isAfter: Boolean, opcode: Byte): Int = when (opcode.toInt() and 0xFF) {
        0x10 -> if (isAfter) TPL_AFTER_CALL else TPL_BEFORE_CALL
        0x11 -> if (isAfter) TPL_AFTER_ALLOC else TPL_BEFORE_ALLOC
        0x12 -> if (isAfter) TPL_AFTER_RETURN else TPL_BEFORE_RETURN
        0x13 -> if (isAfter) TPL_AFTER_PROP else TPL_BEFORE_PROP
        else -> TPL_BEFORE_CALL
    }

    // ── Publish (hot path) ────────────────────────────────────────────

    /**
     * Publish a typedef-adjacent callsite event.
     * Zero string allocation in hot path.
     */
    fun publish(
        opcode: Byte,
        typedefName: String,
        methodName: String,
        siteIdx: Int,
        depth: Byte,
        isAfter: Boolean
    ) {
        if (!active) return

        val tdIdx   = TypedefTable.register(typedefName)
        val mIdx    = InternPool.intern(methodName)
        val csh     = callsiteHash(opcode, mIdx, siteIdx)
        val tpl     = templateIdx(isAfter, opcode)
        val event   = TraceEvent(
            opcode,
            if (isAfter) 1.toByte() else 0.toByte(),
            tdIdx,
            mIdx,
            siteIdx,
            nextSeq(),
            System.nanoTime(),
            depth,
            csh,
            tpl
        )

        ring.add(event)

        // SLAB FIRE: ring hit capacity
        if (ring.a == RING_CAP) {
            flush("fire")
        }
    }

    // Convenience publish for algebra-matched rules
    fun publish(rule: AdjacentRule, typedefName: String, methodName: String, siteIdx: Int, isAfter: Boolean) {
        publish(rule.opcode, typedefName, methodName, siteIdx, rule.depth, isAfter)
    }

    // ── Slab flush ───────────────────────────────────────────────────

    fun flush(reason: String) {
        val count = ring.a
        if (count == 0) return

        val slab = Array(count) { ring.b(it) }

        // clear ring — producer gets fresh scratch
        while (ring.a > 0) { ring.removeAt(0) }

        val epoch = slabEpoch++
        val nanoStart = slab.firstOrNull()?.nano ?: 0L
        val nanoEnd   = slab.lastOrNull()?.nano ?: 0L
        subscriber?.onSlab(slab, count, epoch, nanoStart, nanoEnd)
    }

    fun timeoutFlush() {
        if (ring.a > 0 && ring.a < RING_CAP) {
            flush("timeout")
        }
    }

    // ── Query ────────────────────────────────────────────────────────

    fun size(): Int = ring.a
    fun get(index: Int): TraceEvent = ring.b(index)

    // ── CRMS fold ────────────────────────────────────────────────────

    /**
     * Group slab by callsiteHash → fold BEFORE+AFTER pairs → eigsort by depth.
     * Returns conflict cells sorted by eigenvalue (lattice depth).
     */
    data class ConflictCell(
        val callsiteHash: Int,
        val before: TraceEvent?,
        val after: TraceEvent?,
        val resolved: Boolean,
        val depth: Byte,
        val count: Int
    )

    fun fold(slab: Array<TraceEvent>): List<ConflictCell> {
        val grouped = slab.groupBy { it.callsiteHash }
        return grouped.map { (hash, events) ->
            val before = events.find { it.phase == 0.toByte() }
            val after  = events.find { it.phase == 1.toByte() }
            ConflictCell(
                callsiteHash = hash,
                before = before,
                after  = after,
                resolved = before != null && after != null,
                depth  = before?.depth ?: after?.depth ?: 0,
                count  = events.size
            )
        }.sortedByDescending { it.depth }  // eigsort: deeper typedef = higher priority
    }

    // ── Wireproto ───────────────────────────────────────────────────

    fun wireprotoLength(): Int = size() * RECORD_SIZE

    fun writeRecord(target: java.nio.ByteBuffer, index: Int) {
        val evt = get(index)
        target.put(evt.opcode)
        target.put(evt.phase)
        target.putShort(evt.typedefIdx.toShort())
        target.putInt(evt.methodIdx)
        target.putInt(evt.siteIdx)
        target.putInt(evt.seq)
        target.putLong(evt.nano)
        target.put(evt.depth)
        target.put(0)  // pad
        target.putShort(evt.callsiteHash.toShort())
    }

    fun drainToWireproto(): java.nio.ByteBuffer {
        val buf = java.nio.ByteBuffer.allocate(wireprotoLength())
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until size()) {
            writeRecord(buf, i)
        }
        buf.flip()
        return buf
    }

    // ── Reset ───────────────────────────────────────────────────────

    fun reset() {
        active = false
        while (ring.a > 0) { ring.removeAt(0) }
        seq = 0
        slabEpoch = 0
        subscriber = null
    }
}