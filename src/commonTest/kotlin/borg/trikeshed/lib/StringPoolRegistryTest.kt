package borg.trikeshed.lib

import borg.trikeshed.charstr.CharStr
import borg.trikeshed.charstr.*
import kotlin.test.*

/**
 * TDD RED — StringPool Registry + CRMS Facets on CharStr + wireproto reify/redux template.
 *
 * Proposal: xvm modules self-register StringPools keyed by wireproto coordinates.
 * CharStr Facets flow through ReduxMutableSeries → reify() → wireproto payload.
 *
 * wireproto nexus: (poolId, offset, length) — the 24B wire header for a CharStr payload.
 */
class StringPoolRegistryTest {

    /** Monotonic nanosecond-ish counter — portable across all Kotlin targets. */
    private var nanoCounter = 0L
    private fun nanoTime(): Long = nanoCounter++

    // ── Wireproto coordinates (24B header) ──────────────────────────

    /** 24-byte wireproto header: [poolId(8)][offset(8)][length(8)] BigEndian. */
    data class WireCoords(
        val poolId: Long,
        val offset: Long,
        val length: Long,
    ) {
        companion object {
            val SIZE_BYTES = 24
            fun fromBytes(buf: ByteArray, off: Int = 0): WireCoords {
                require(buf.size >= off + SIZE_BYTES)
                return WireCoords(
                    buf[off].toLong() and 0xFF shl 56 or
                            buf[off + 1].toLong() and 0xFF shl 48 or
                            buf[off + 2].toLong() and 0xFF shl 40 or
                            buf[off + 3].toLong() and 0xFF shl 32 or
                            buf[off + 4].toLong() and 0xFF shl 24 or
                            buf[off + 5].toLong() and 0xFF shl 16 or
                            buf[off + 6].toLong() and 0xFF shl 8 or
                            buf[off + 7].toLong() and 0xFF,
                    0L, 0L
                )
            }
        }

        fun toBytes(): ByteArray {
            val b = ByteArray(SIZE_BYTES)
            for (i in 0..7) b[i] = ((poolId shr (56 - i * 8)) and 0xFF).toByte()
            for (i in 0..7) b[8 + i] = ((offset shr (56 - i * 8)) and 0xFF).toByte()
            for (i in 0..7) b[16 + i] = ((length shr (56 - i * 8)) and 0xFF).toByte()
            return b
        }
    }

    // ── StringPool facet — one pool per xvm module ────────────────────

    /** A StringPool that can be registered in the global xvm registry. */
    interface StringPoolFacet {
        val poolId: Long
        val poolName: String
        fun encode(key: String): WireCoords
        fun decode(coords: WireCoords): String?
        val sizeBytes: Int
    }

    /** Simple inline StringPool using a growing ByteArray buffer. */
    class InlineStringPool(override val poolId: Long, override val poolName: String) : StringPoolFacet {
        private val strings = mutableListOf<String>()
        private val offsets = mutableListOf<Long>()

        fun register(payload: String): Long {
            offsets.add(strings.size.toLong())
            strings.add(payload)
            return offsets.lastIndex.toLong()
        }

        override fun encode(key: String): WireCoords {
            val idx = strings.indexOf(key).takeIf { it >= 0 }
                ?: error("StringPool(${poolName}): key not registered: $key")
            return WireCoords(poolId, offsets[idx], key.length.toLong())
        }

        override fun decode(coords: WireCoords): String? {
            if (coords.poolId != poolId) return null
            val idx = coords.offset.toInt()
            return strings.getOrNull(idx)?.take(coords.length.toInt())
        }

        override val sizeBytes: Int get() = strings.sumOf { it.length * 2 } // UTF-16
    }

    // ── Global registry ──────────────────────────────────────────────

    /** xvm module StringPool registry — modules self-register. */
    object StringPoolRegistry {
        private val pools = mutableMapOf<Long, StringPoolFacet>()
        private val byName = mutableMapOf<String, StringPoolFacet>()

        fun register(pool: StringPoolFacet) {
            pools[pool.poolId] = pool
            byName[pool.poolName] = pool
        }

        fun get(poolId: Long): StringPoolFacet? = pools[poolId]

        fun getByName(name: String): StringPoolFacet? = byName[name]

        val registeredPools: Collection<StringPoolFacet> get() = pools.values

        // PoolId counter for auto-assignment
        private var nextId = 1L
        fun nextPoolId(): Long = nextId++
    }

    // ── Wireproto payload type for CharStr Facet events ─────────────

    /** Wireproto payload for a CharStr Facet dispatch. */
    data class CharStrFacetPayload(
        val wireCoords: WireCoords,
        val facetOp: TextK<*>,
        val timestampNanos: Long = 0L
    )

    // ── Reducer for CharStr Facet events ───────────────────────────

    class CharStrFacetReducer : Reducer<CharStrFacetPayload, Series<CharStr>> {
        override val zero: Series<CharStr> = emptySeriesOf()

        override fun combine(state: Series<CharStr>, action: CharStrFacetPayload): Series<CharStr> {
            // Decode from wireproto coords → actual string
            val pool = StringPoolRegistry.get(action.wireCoords.poolId) ?: return state
            val decoded = pool.decode(action.wireCoords) ?: return state
            val cs = CharStr(decoded)
            // Extend series: (n+1) j { idx -> if idx < n then state[idx] else newElement }
            val n = state.size
            return (n + 1) j { idx -> if (idx < n) state[idx] else cs }
        }
    }

    // ── TESTS ───────────────────────────────────────────────────────

    @Test
    fun `StringPoolRegistry registers and retrieves pools by id and name`() {
        val pool = InlineStringPool(0L, "test-pool")
        StringPoolRegistry.register(pool)

        assertSame(pool, StringPoolRegistry.get(0L))
        assertSame(pool, StringPoolRegistry.getByName("test-pool"))
        assertEquals(1, StringPoolRegistry.registeredPools.size)
    }

    @Test
    fun `InlineStringPool round-trips register and decode`() {
        val pool = InlineStringPool(42L, "nexus-pool")
        val key0 = pool.register("hello, wireproto!")
        val key1 = pool.register("CharStr Facet payload")

        val coords0 = WireCoords(42L, 0L, 19L)
        val coords1 = WireCoords(42L, 1L, 22L)

        assertEquals("hello, wireproto!", pool.decode(coords0))
        assertEquals("CharStr Facet payload", pool.decode(coords1))
    }

    @Test
    fun `WireCoords round-trip through ByteArray`() {
        val original = WireCoords(0xDEAD_BEEF_CAFEL, 0x1234_5678_ABCD_EFL, 99_999_999_999L)
        val bytes = original.toBytes()
        assertEquals(WireCoords.SIZE_BYTES, bytes.size)

        val decoded = WireCoords.fromBytes(bytes)
        assertEquals(original.poolId, decoded.poolId)
        assertEquals(original.offset, decoded.offset)
        assertEquals(original.length, decoded.length)
    }

    @Test
    fun `ReduxMutableSeries reify produces CharStr series from facet events`() {
        // Setup: register a pool
        val pool = InlineStringPool(7L, "crms-facet-pool")
        StringPoolRegistry.register(pool)

        // Register payload
        pool.register("facet-event-alpha")
        pool.register("facet-event-beta")

        // Redux journal
        val journal = ReduxMutableSeries(
            eventJournal = ChunkedMutableSeries<CharStrFacetPayload>(),
            reducer = CharStrFacetReducer(),
            capture = CharStrFacetPayload(
                WireCoords(7L, 0L, 0L),
                TextK.Raw,
                nanoTime()
            )
        )

        // Dispatch facet events via wireproto coords
        val coordsAlpha = pool.encode("facet-event-alpha")
        val coordsBeta = pool.encode("facet-event-beta")

        journal.dispatch(CharStrFacetPayload(coordsAlpha, TextK.Raw, nanoTime()))
        journal.dispatch(CharStrFacetPayload(coordsBeta, TextK.Raw, nanoTime()))

        // reify() — the wireproto redux template
        val state = journal.reify()

        assertEquals(2, state.size)
        assertEquals("facet-event-alpha", state[0].toString())
        assertEquals("facet-event-beta", state[1].toString())
    }

    @Test
    fun `CharStr Facet dispatches wireproto coords to ReduxMutableSeries`() {
        val pool = InlineStringPool(99L, "charstr-dispatch-pool")
        StringPoolRegistry.register(pool)

        pool.register("alpha")
        pool.register("beta")
        pool.register("gamma")

        val journal = ReduxMutableSeries(
            eventJournal = RingSeries<CharStrFacetPayload>(capacity = 8),
            reducer = CharStrFacetReducer(),
            capture = CharStrFacetPayload(WireCoords(0, 0, 0), TextK.Raw, 0L)
        )

        val coords = listOf(
            pool.encode("alpha"),
            pool.encode("beta"),
            pool.encode("gamma"),
        )

        coords.forEach { c ->
            journal.dispatch(CharStrFacetPayload(c, TextK.Raw, nanoTime()))
        }

        val state = journal.reify()
        assertEquals(3, state.size)
        assertEquals("alpha", state[0].toString())
        assertEquals("beta", state[1].toString())
        assertEquals("gamma", state[2].toString())
    }

    @Test
    fun `WireCoords poolId mismatch returns null from decode`() {
        val poolA = InlineStringPool(1L, "pool-A")
        val poolB = InlineStringPool(2L, "pool-B")
        StringPoolRegistry.register(poolA)
        StringPoolRegistry.register(poolB)

        poolA.register("from-A")

        val coordsFromA = poolA.encode("from-A")
        // Try to decode with wrong poolId
        val wrongCoords = WireCoords(2L, coordsFromA.offset, coordsFromA.length)

        assertNull(poolB.decode(wrongCoords))
    }

    @Test
    fun `ReduxMutableSeries state property delegates to reify`() {
        val pool = InlineStringPool(5L, "state-test-pool")
        StringPoolRegistry.register(pool)
        pool.register("x")
        pool.register("y")
        pool.register("z")

        val journal = ReduxMutableSeries(
            eventJournal = ChunkedMutableSeries<CharStrFacetPayload>(),
            reducer = CharStrFacetReducer(),
            capture = CharStrFacetPayload(WireCoords(0, 0, 0), TextK.Raw, 0L)
        )

        assertEquals(0, journal.state.size) // empty initially

        journal.dispatch(CharStrFacetPayload(pool.encode("x"), TextK.Raw, nanoTime()))
        assertEquals(1, journal.state.size) // lazy reify on access

        journal.dispatch(CharStrFacetPayload(pool.encode("y"), TextK.Raw, nanoTime()))
        journal.dispatch(CharStrFacetPayload(pool.encode("z"), TextK.Raw, nanoTime()))
        assertEquals(3, journal.state.size)
    }
}
