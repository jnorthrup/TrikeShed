package org.xvm.cursor

import borg.trikeshed.lib.ChunkedMutableSeries
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.MutableSeries
import borg.trikeshed.lib.ReduxMutableSeries
import borg.trikeshed.lib.RingSeries
import borg.trikeshed.lib.Series

/**
 * Pointcut registration system — replaces interception target with a
 * (T) -> T unary function that handles List->MutableSeries->List codec.
 *
 * Architecture:
 *   Field access (getter/setter) → PointcutRegistry.intercept(opcode, value)
 *     → codec.encode(value)  (List → MutableSeries)
 *     → stored in RingSeries shim
 *     → later PointcutRegistry.read(opcode, addr) → codec.decode()  (MutableSeries → List)
 *
 * Shim/data separation:
 *   Shim (control plane): head/tail/epoch — zero-GC, reset per tick
 *   Data (payload): mmap records ranked by cascade rollup via Confix re-join
 *
 * The unary codec function IS the stable facade — RingSeries is the only
 * MutableSeries that survives firehose rates (>1K events/sec) without
 * GC pressure from O(n) COW on every mutation.
 *
 * Wireproto: opcode byte IS the codec selector (1 byte, 0-255).
 */
object PointcutRegistry {

    /**
     * A unary codec function that encodes a value into a MutableSeries
     * and decodes back to the original type.
     *
     * encode:  T → MutableSeries<T>   (List → RingSeries → Chunked)
     * decode:  MutableSeries<T> → T    (RingSeries → List)
     *
     * The codec is stateful — encodes into RingSeries(shim) on write,
     * decodes from RingSeries on read — the handle is stable across mutations.
     */
    interface PointcutCodec<T> {
        fun encode(value: T, ring: RingSeries<T>): Unit
        fun decode(ring: RingSeries<T>, index: Int): T
        fun decodeAll(ring: RingSeries<T>): Series<T>
    }

    // ── Default codec: single-item ring with no compression ───────────────

    /**
     * Default codec — stores one item at a time in RingSeries.
     * Decode returns the most recent write (index = head - 1 mod capacity).
     *
     * For complex aggregation, replace with MergeCodec, ChunkedCodec, etc.
     */
    class DefaultCodec<T> : PointcutCodec<T> {
        override fun encode(value: T, ring: RingSeries<T>) {
            ring.add(value)  // O(1), zero-GC
        }

        override fun decode(ring: RingSeries<T>, index: Int): T {
            return ring.b(index)
        }

        override fun decodeAll(ring: RingSeries<T>): Series<T> {
            val sz = ring.a
            return object : Join<Int, (Int) -> T> {
                override val a = sz
                override val b: (Int) -> T = { index -> ring.b(index) }
            }
        }
    }

    /**
     * Reduction codec — fold-on-read, last-write-wins per key.
     * The reducer function maps T → K to group, and acc + T → acc to accumulate.
     * On decodeAll, returns the folded state map.
     */
    class ReductionCodec<T, S>(
        private val reducer: borg.trikeshed.lib.Reducer<T, S>,
        private val capture: T
    ) : PointcutCodec<T> {
        override fun encode(value: T, ring: RingSeries<T>) {
            ring.add(value)
        }

        override fun decode(ring: RingSeries<T>, index: Int): T {
            return ring.b(index)
        }

        override fun decodeAll(ring: RingSeries<T>): Series<T> {
            val sz = ring.a
            return object : Join<Int, (Int) -> T> {
                override val a = sz
                override val b: (Int) -> T = { index -> ring.b(index) }
            }
        }
    }

    // ── Opcode → codec registry ───────────────────────────────────────────

    /**
     * Codecs keyed by (opcode: Int, phase: String).
     * The opcode byte IS the codec selector.
     */
    private val codecs = mutableMapOf<Int, PointcutCodec<*>>()
    private val phaseOf = mutableMapOf<Int, String>()

    /**
     * Register a codec for an opcode range.
     * @param fromOpcode  start of opcode range (inclusive)
     * @param toOpcode    end of opcode range (inclusive)
     * @param phase       canonical phase name (CONSTRUCTOR, GETTER, SETTER, ALLOC, etc.)
     * @param codec       unary codec function
     */
    fun <T> register(fromOpcode: Int, toOpcode: Int, phase: String, codec: PointcutCodec<T>) {
        for (op in fromOpcode..toOpcode) {
            codecs[op] = codec
            phaseOf[op] = phase
        }
    }

    /**
     * Register a single opcode with its codec.
     */
    fun <T> register(opcode: Int, phase: String, codec: PointcutCodec<T>) {
        codecs[opcode] = codec
        phaseOf[opcode] = phase
    }

    // ── Pre-built default registrations ───────────────────────────────────

    /**
     * Install the default XVM pointcut codec set.
     * Call once at startup — replaces all null codec slots with DefaultCodec.
     */
    fun installDefaults() {
        val default = DefaultCodec<Any>()

        // CONSTRUCTOR: 0x34-0x37
        register(0x34, 0x37, "CONSTRUCTOR", default)
        // GETTER: 0xA5 (L_GET), 0xA7 (P_GET)
        register(0xA5, "GETTER", default)
        register(0xA7, "GETTER", default)
        // SETTER: 0xA6 (L_SET), 0xA8 (P_SET)
        register(0xA6, "SETTER", default)
        register(0xA8, "SETTER", default)
        // ALLOC: 0x38-0x3B, 0x40-0x43, 0x48-0x4B
        register(0x38, 0x3B, "ALLOC", default)
        register(0x40, 0x43, "ALLOC", default)
        register(0x48, 0x4B, "ALLOC", default)
        // CALL: 0x10-0x1F, 0x20-0x2F
        register(0x10, 0x1F, "CALL", default)
        register(0x20, 0x2F, "CALL", default)
        // RETURN: 0x4C-0x4F
        register(0x4C, 0x4F, "RETURN", default)
    }

    // ── Query ────────────────────────────────────────────────────────────

    fun codecOf(opcode: Int): PointcutCodec<*>? = codecs[opcode]

    fun phaseOf(opcode: Int): String = phaseOf[opcode] ?: "GAP"

    fun isRegistered(opcode: Int): Boolean = opcode in codecs

    // ── Intercept / Read ─────────────────────────────────────────────────

    /**
     * Intercept a pointcut event: encode the value into the shim's RingSeries.
     *
     * @param opcode   wireproto opcode byte
     * @param value    the value to encode (field value, constructor arg, etc.)
     * @param ring     the shim's RingSeries (stable handle across mutations)
     */
    fun <T> intercept(opcode: Int, value: T, ring: RingSeries<T>) {
        val codec = codecs[opcode] as? PointcutCodec<T>
            ?: throw IllegalStateException("No codec registered for opcode 0x${Integer.toHexString(opcode)}")
        codec.encode(value, ring)
    }

    /**
     * Read a decoded value from the shim's RingSeries at logical index.
     *
     * @param opcode  wireproto opcode byte
     * @param ring    the shim's RingSeries
     * @param index   logical index (0 = oldest)
     */
    fun <T> read(opcode: Int, ring: RingSeries<T>, index: Int): T {
        val codec = codecs[opcode] as? PointcutCodec<T>
            ?: throw IllegalStateException("No codec registered for opcode 0x${Integer.toHexString(opcode)}")
        return codec.decode(ring, index)
    }

    /**
     * Decode all values from the shim's RingSeries.
     * Returns Series<T> — the full decode of the MutableSeries back to a cursor-native view.
     */
    fun <T> readAll(opcode: Int, ring: RingSeries<T>): Series<T> {
        val codec = codecs[opcode] as? PointcutCodec<T>
            ?: throw IllegalStateException("No codec registered for opcode 0x${Integer.toHexString(opcode)}")
        return codec.decodeAll(ring)
    }

    // ── Debug ───────────────────────────────────────────────────────────

    fun dumpRegistrations(): Map<Int, String> {
        return phaseOf.toList().associate { it.first to it.second }
    }
}