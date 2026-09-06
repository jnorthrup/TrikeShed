package borg.trikeshed.parse.confix

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ConfixFacetLazinessTest {
    private class Source(val bytes: ByteArray) {
        var reads = 0
        val series: Series<Byte> = bytes.size j { index: Int ->
            reads++
            bytes[index]
        }
    }

    @Test
    fun scanningAndGeometryDoNotDecodeKeysOrHashSource() {
        val fixtures = listOf(
            Syntax.JSON to """{"key":"value","nested":[1,true,null]}""".encodeToByteArray(),
            Syntax.CBOR to byteArrayOf(0xa1.toByte(), 0x61, 0x6b, 0x01),
            Syntax.YAML to "key: value\n".encodeToByteArray(),
        )
        for ((syntax, bytes) in fixtures) {
            val source = Source(bytes)
            when (syntax) {
                Syntax.JSON -> syntax.scan0(source.series)
                Syntax.CBOR -> syntax.scanCbor0(source.series)
                Syntax.YAML -> syntax.scanYaml0(source.series)
            }
            val scanReads = source.reads
            source.reads = 0
            val index = syntax.scanIndex(source.series)
            assertEquals(scanReads, source.reads, "$syntax scanIndex must only scan")

            source.reads = 0
            index.facet(ConfixIndexK.Spans)[0]
            index.facet(ConfixIndexK.Tags)[0]
            index.facet(ConfixIndexK.Depths)[0]
            index.facet(ConfixIndexK.DirectChildren)(0)
            index.facet(ConfixIndexK.TreeCursor)[0]
            index.facet(ConfixIndexK.KeyToChild)
            assertEquals(0, source.reads, "$syntax geometry must not read token payloads")
        }
    }

    @Test
    fun keyIndexIsBuiltOnFirstLookupAndReused() {
        val source = Source("""{"\u006b":"first","nested":{"k":"second"}}""".encodeToByteArray())
        val index = Syntax.JSON.scanIndex(source.series)
        source.reads = 0
        val lookup = index.facet(ConfixIndexK.KeyToChild)
        assertEquals(0, source.reads)
        assertEquals(1, lookup("k"))
        assertTrue(source.reads > 0)
        source.reads = 0
        assertEquals(1, lookup(StringBuilder("k")))
        assertNotNull(index.facet(ConfixIndexK.KeyToChild)("nested"))
        assertNull(lookup("missing"))
        assertEquals(0, source.reads, "Repeated lookups must reuse decoded keys")
    }

    @Test
    fun structuralIdsRetainTheirEncodingAndAreMemoized() {
        val fixtures = listOf(
            Syntax.JSON to listOf("[1,\"x\"]".encodeToByteArray(), "1".encodeToByteArray(), "\"x\"".encodeToByteArray()),
            Syntax.CBOR to listOf(byteArrayOf(0x82.toByte(), 0x01, 0x61, 0x78), byteArrayOf(0x01), byteArrayOf(0x61, 0x78)),
        )
        for ((syntax, bytes) in fixtures) {
            val source = Source(bytes[0])
            val index = syntax.scanIndex(source.series)
            source.reads = 0
            val ids = index.facet(ConfixIndexK.StructuralNodes)
            assertTrue(source.reads > 0)
            val first = ContentId.of(bytes[1]).value
            val second = ContentId.of(bytes[2]).value
            val root = ContentId.of("node:\n$first\n$second\n".encodeToByteArray()).value
            assertEquals(3, ids.size)
            assertEquals(root, ids[0])
            assertEquals(first, ids[1])
            assertEquals(second, ids[2])

            source.reads = 0
            assertSame(ids, index.facet(ConfixIndexK.StructuralNodes))
            assertEquals(root, ids[0])
            assertEquals(0, source.reads, "$syntax hashes must be reused")
        }
    }

    @Test
    fun derivedFacetsInitializeIndependently() {
        for (keysFirst in listOf(false, true)) {
            val source = Source("""{"key":"value"}""".encodeToByteArray())
            val index = Syntax.JSON.scanIndex(source.series)
            fun keys() = index.facet(ConfixIndexK.KeyToChild)("key")
            fun hashes() = index.facet(ConfixIndexK.StructuralNodes)
            source.reads = 0
            if (keysFirst) keys() else hashes()
            assertTrue(source.reads > 0)
            source.reads = 0
            if (keysFirst) hashes() else keys()
            assertTrue(source.reads > 0, "Building one facet must not initialize the other")
            source.reads = 0
            assertNotNull(keys())
            hashes()
            assertEquals(0, source.reads)
        }
    }

    @Test
    fun truncatedCborPayloadsFailBeforeDerivedFacetsAreRequested() {
        for (bytes in listOf(
            byteArrayOf(0x65, 0x61),
            byteArrayOf(0x45, 0x01),
            byteArrayOf(0xfa.toByte(), 0x00),
        )) {
            assertFails { Syntax.CBOR.scanIndex(bytes.toSeries()) }
        }
    }

    @Test
    fun emptyIndexesHaveEmptyDerivedFacets() {
        val index = Syntax.JSON.scanIndex(byteArrayOf().toSeries())
        assertEquals(0, index.facet(ConfixIndexK.StructuralNodes).size)
        assertNull(index.facet(ConfixIndexK.KeyToChild)("missing"))
    }
}
