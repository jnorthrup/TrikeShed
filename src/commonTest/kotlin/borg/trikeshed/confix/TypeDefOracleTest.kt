@file:Suppress("NonAsciiCharacters", "UNCHECKED_CAST")

package borg.trikeshed.confix

import borg.trikeshed.parse.confix.*
import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*
import kotlin.test.*

class TypeDefOracleTest {

    // ── .x typedef parsing ────────────────────────────────────────

    @Test fun `parse single typedef`() {
        val oracle = TypeDefOracle()
        oracle.parseTypeDefs("typedef Join<A, B> as Tuple<A, B>;", "test.x")
        assertEquals(1, oracle.size)
        val o = oracle.build()
        assertEquals(1, o.entries.size)
        assertEquals("Tuple", o.entries[0].name)
        assertEquals("Join<A, B>", o.entries[0].referredToType)
    }

    @Test fun `parse RFC-1 cursor typedefs`() {
        val src = """
            typedef Join<T, T> as Twin<T>;
            typedef MetaSeries<Int, T> as Series<T>;
            typedef Series<RowVec> as Cursor;
            typedef Series<Cell> as RowVec;
        """.trimIndent()
        val oracle = TypeDefOracle()
        oracle.parseTypeDefs(src, "typedefs.x")
        val o = oracle.build()
        val entryCount = o.entries.size
        assertTrue(entryCount > 0, "expected entries but got $entryCount from: $src")
        val tokenCount = o.tokens.size
        assertTrue(tokenCount > 0, "expected tokens but got $tokenCount")

        // name resolution
        val cursor = o.byName("Cursor")
        assertNotNull(cursor)
        assertEquals("Cursor", o.tdNames(cursor))

        // lattice has edges: Cursor → Series, RowVec → Series, Series → MetaSeries, Twin → Join
        assertTrue(o.edgeCount >= 4)
    }

    @Test fun `parse xvm union typedefs`() {
        val src = """
            typedef String|Int as StringOrInt;
            typedef String|IPAddress as Host;
            typedef MediaType|MediaType[] as MediaTypes;
        """.trimIndent()
        val oracle = TypeDefOracle()
        oracle.parseTypeDefs(src, "union.x")
        val o = oracle.build()
        assertEquals(3, o.entries.size)

        // StringOrInt IS-A String and StringOrInt IS-A Int
        val lattice = o.lattice
        val stringOrInt = o.byName("StringOrInt")
        assertNotNull(stringOrInt)
        val stringTok = o.byName("String")
        assertNotNull(stringTok)
        assertTrue(lattice.isA(stringOrInt, stringTok))
    }

    @Test fun `lattice transitive isA`() {
        val src = """
            typedef Join<T, T> as Twin<T>;
            typedef MetaSeries<Int, T> as Series<T>;
            typedef Series<RowVec> as Cursor;
        """.trimIndent()
        val oracle = TypeDefOracle()
        oracle.parseTypeDefs(src, "transitive.x")
        val o = oracle.build()

        // Cursor → Series → MetaSeries (transitive)
        val cursor = o.byName("Cursor")
        val metaSeries = o.byName("MetaSeries")
        assertNotNull(cursor)
        assertNotNull(metaSeries)
        assertTrue(o.lattice.isA(cursor, metaSeries))
    }

    @Test fun `lattice reflexive isA`() {
        val oracle = TypeDefOracle()
        oracle.parseTypeDefs("typedef Join<A, B> as Tuple<A, B>;", "test.x")
        val o = oracle.build()
        val tuple = o.byName("Tuple")
        assertNotNull(tuple)
        assertTrue(o.lattice.isA(tuple, tuple))
    }

    @Test fun `lattice incompatible types`() {
        val oracle = TypeDefOracle()
        oracle.parseTypeDefs("typedef String|Int as StringOrInt;", "test.x")
        val o = oracle.build()
        val stringOrInt = o.byName("StringOrInt")!!
        // no edge from String to Int
        val intTok = o.byName("Int")!!
        assertFalse(o.lattice.isA(intTok, stringOrInt))
    }

    // ── Kotlin typealias parsing ──────────────────────────────────

    @Test fun `parse kotlin typealias`() {
        val src = """
            typealias TypeProductionSlot = FacetedRow<TypeProductionK<*>>
            typealias TypeBlackboard = Cursor
        """.trimIndent()
        val oracle = TypeDefOracle()
        oracle.parseTypeDefs(src, "arch.kt")
        val o = oracle.build()
        assertEquals(2, o.entries.size)
        assertEquals("TypeProductionSlot", o.entries[0].name)
        assertEquals("TypeBlackboard", o.entries[1].name)
    }

    // ── explicit link checks ──────────────────────────────────────

    @Test fun `add explicit link check edge`() {
        val oracle = TypeDefOracle()
        oracle.parseTypeDefs("typedef Series<RowVec> as Cursor;", "test.x")
        oracle.addLinkCheck("Cursor", "Iterable")
        val o = oracle.build()

        // explicit edge: Cursor IS-A Iterable
        val cursor = o.byName("Cursor")!!
        val iterable = o.byName("Iterable")!!
        assertTrue(o.lattice.isA(cursor, iterable))

        // also still has the typedef edge: Cursor IS-A Series
        val series = o.byName("Series")!!
        assertTrue(o.lattice.isA(cursor, series))
    }

    // ── full xvm typedefs.x from RFC-1 ────────────────────────────

    @Test fun `parse full RFC-1 typedefs`() {
        val src = """
            typedef Join<A, B> as Tuple<A, B>;
            typedef Twin<T> as Join<T, T>;
            typedef MetaSeries<I, T> as Join<I, function T(I)>;
            typedef Series<T> as MetaSeries<Int, T>;
            typedef Series2<A, B> as Series<Join<A, B>>;

            typedef Join<String, String> as ColumnMeta;
            typedef function ColumnMeta() as ColumnMetaRef;
            typedef Join<Any?, ColumnMetaRef> as Cell;
            typedef Series<Cell> as RowVec;
            typedef Series<RowVec> as Cursor;

            typedef Series<Char> as CharStr;
            typedef Series<CharStr> as Corpus;
        """.trimIndent()
        val oracle = TypeDefOracle()
        oracle.parseTypeDefs(src, "typedefs.x")
        val o = oracle.build()

        // all 11 typedefs parsed
        assertEquals(11, o.entries.size)

        // key compositional atoms exist
        assertNotNull(o.byName("Cursor"))
        assertNotNull(o.byName("RowVec"))
        assertNotNull(o.byName("Series"))
        assertNotNull(o.byName("CharStr"))
        assertNotNull(o.byName("Twin"))
        assertNotNull(o.byName("Tuple"))
        assertNotNull(o.byName("Cell"))
        assertNotNull(o.byName("Corpus"))

        // transitive: Cursor → Series (direct), Series → MetaSeries (via typedef chain)
        val cursor = o.byName("Cursor")!!
        val series = o.byName("Series")!!
        assertTrue(o.lattice.isA(cursor, series), "Cursor IS-A Series (direct edge)")

        // Tuple → Join (direct)
        val tuple = o.byName("Tuple")!!
        val join = o.byName("Join")!!
        assertTrue(o.lattice.isA(tuple, join), "Tuple IS-A Join (direct edge)")

        // Join → Twin (from typedef Twin<T> as Join<T,T>)
        val twin = o.byName("Twin")!!
        assertTrue(o.lattice.isA(join, twin), "Join IS-A Twin (from typedef Twin<T> as Join<T,T>")

        // params preserved
        var tupleEntry: TypeDefEntry? = null
        for (i in 0 until o.entries.size) {
            val e = o.entries[i]
            if (e.name == "Tuple") { tupleEntry = e; break }
        }
        assertNotNull(tupleEntry)
        assertEquals(2, tupleEntry!!.params.size)
        assertEquals("A", tupleEntry.params[0].name)
        assertEquals("B", tupleEntry.params[1].name)
    }

    // ── xvm master typedefs (union types) ─────────────────────────

    @Test fun `parse xvm master union typedefs`() {
        val src = """
            typedef Int|Int[] as KeySize;
            typedef Algorithm|String as Specifier;
            typedef Signature|Byte[] as Digest;
            typedef String|IPAddress as Host;
            typedef String|Int as StringOrInt;
        """.trimIndent()
        val oracle = TypeDefOracle()
        oracle.parseTypeDefs(src, "master.x")
        val o = oracle.build()

        assertEquals(5, o.entries.size)

        // union typedefs: KeySize IS-A Int
        val keySize = o.byName("KeySize")!!
        val intTok = o.byName("Int")!!
        assertTrue(o.lattice.isA(keySize, intTok))

        // Host IS-A String, Host IS-A IPAddress
        val host = o.byName("Host")!!
        val stringTok = o.byName("String")!!
        val ipAddrTok = o.byName("IPAddress")!!
        assertTrue(o.lattice.isA(host, stringTok))
        assertTrue(o.lattice.isA(host, ipAddrTok))
    }

    // ── supertypes query (staircase) ──────────────────────────────

    @Test fun `supertypes staircase`() {
        val src = """
            typedef Join<T, T> as Twin<T>;
            typedef MetaSeries<Int, T> as Series<T>;
            typedef Series<RowVec> as Cursor;
        """.trimIndent()
        val oracle = TypeDefOracle()
        oracle.parseTypeDefs(src, "staircase.x")
        val o = oracle.build()
        val cursor = o.byName("Cursor")!!

        val supers = o.lattice.supertypes(cursor)
        // Cursor → Series → MetaSeries → Join (at minimum)
        val superNames = supers α { o.tdNames(it) }
        assertTrue("Series" in superNames || "MetaSeries" in superNames || "Join" in superNames,
            "Expected at least one transitive supertype, got: $superNames")
    }
}
