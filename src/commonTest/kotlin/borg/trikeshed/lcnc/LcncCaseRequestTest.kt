package borg.trikeshed.lcnc

import borg.trikeshed.collections.associative.*
import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.*
import kotlin.test.*

class LcncCaseRequestTest {
    @Test fun initializerRetainsNestedFractionsAndExactLongsAcrossItsActualCaseBlob() {
        val cas = CasStore.inMemory()
        val program = ContentId.of("case-program".encodeToByteArray())
        val inputs = mapOf("brief" to "Motion: retain numeric evidence", "nested" to mapOf(
            "values" to listOf(2.5, Long.MAX_VALUE), "negative" to -3.25))
        val retained = LcncCaseFrame.retain(cas, LcncCaseRequest.Initialize("matter", "open"), program, inputs)
        val bytes = assertNotNull(cas.get(retained.cid))
        assertEquals(retained.cid, ContentId.of(bytes))
        val root = assertIs<Item.Map>(Cbor.decode(bytes))
        val encodedInputs = assertIs<Item.Map>(root["inputs"])
        val nested = assertIs<Item.Map>(encodedInputs["nested"])
        val values = assertIs<Item.Arr>(nested["values"])
        assertEquals(2.5, assertIs<Item.Flt>(values[0]).value, "The actual frame must contain a CBOR float, not integer 2")
        assertEquals(Long.MAX_VALUE, assertIs<Item.Num>(values[1]).value)
        assertEquals(-3.25, assertIs<Item.Flt>(nested["negative"]).value)
        val loaded = LcncCaseFrame.load(cas, retained.cid)
        val decoded = assertIs<Map<*, *>>(loaded.inputs["nested"])
        val actual = assertIs<List<*>>(decoded["values"])
        assertEquals(2.5, assertIs<Double>(actual[0]))
        assertEquals(Long.MAX_VALUE, assertIs<Long>(actual[1]))
        assertEquals(-3.25, assertIs<Double>(decoded["negative"]))
    }

    @Test fun rawTransitionKeepsTheOriginalNumericTokenAndRefusesMixedOrDuplicateFields() {
        val head = ContentId.of("head".encodeToByteArray())
        fun source(revision: String) = """{"case":{"intent":"transition","caseId":"matter","idempotencyKey":"move","expectedRevision":$revision,"priorStateCid":"${head.value}","requestedTarget":"argue"}}"""
        fun parse(source: String) = lcncCaseRequest(confixDoc(source.encodeToByteArray(), Syntax.JSON))
        val exact = assertIs<LcncCaseRequest.Transition>(parse(source(Long.MAX_VALUE.toString())))
        assertEquals(Long.MAX_VALUE, exact.expectedRevision)
        assertEquals(2L, assertIs<LcncCaseRequest.Transition>(parse(source("2.0"))).expectedRevision)
        for (invalid in listOf("2.0000000000000000001", "2.5", "9223372036854775808", "\"2\"", "-1"))
            assertFailsWith<LcncCaseException>(invalid) { parse(source(invalid)) }
        assertFailsWith<LcncCaseException> { parse(source("2").replace("\"expectedRevision\":2", "\"expectedRevision\":2,\"expectedRevision\":3")) }
        assertFailsWith<LcncCaseException> { parse(source("2").replace("{\"case\":", "{\"pulse\":{},\"case\":")) }
        assertFailsWith<LcncCaseException> { parse(source("2").replace("\"requestedTarget\":\"argue\"", "\"requestedTarget\":\"argue\",\"requestCid\":\"${head.value}\"")) }
        val cas = CasStore.inMemory()
        val retained = LcncCaseFrame.retain(cas, exact, ContentId.of("program".encodeToByteArray()), emptyMap())
        assertEquals(Long.MAX_VALUE, assertIs<LcncCaseRequest.Transition>(LcncCaseFrame.load(cas, retained.cid).request).expectedRevision)
    }
}
