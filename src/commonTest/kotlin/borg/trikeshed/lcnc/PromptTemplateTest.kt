package borg.trikeshed.lcnc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** A stored prompt is a citizen: its text has holes, its bytes have one identity, its lineage is inside. */
class PromptTemplateTest {

    @Test
    fun variablesAreDeclaredOrderDeduplicated() {
        assertEquals(listOf("a", "b"), PromptTemplate.variables("{{a}} then {{ b }} then {{a}} again"))
        assertEquals(emptyList(), PromptTemplate.variables("no holes here"))
        assertEquals(listOf("doc.title"), PromptTemplate.variables("{{doc.title}}"))
    }

    @Test
    fun renderBindsEveryHoleOrThrowsNamingTheUnboundOnes() {
        assertEquals("hello world, 3 times", PromptTemplate.render("hello {{who}}, {{n}} times", mapOf("who" to "world", "n" to 3)))
        val e = assertFailsWith<IllegalArgumentException> {
            PromptTemplate.render("{{a}} and {{b}} and {{c}}", mapOf("b" to "x"))
        }
        assertTrue("{{a}}" in e.message!! && "{{c}}" in e.message!!, e.message)
        assertTrue("{{b}}" !in e.message!!, "a bound hole is not reported: ${e.message}")
    }

    @Test
    fun renderNeverLeavesAPartialResult() {
        // A json value renders as json, a null as the word null — never as an empty hole.
        assertEquals("[1, 2] / null", PromptTemplate.render("{{xs}} / {{none}}", mapOf("xs" to listOf(1, 2), "none" to null)))
    }

    @Test
    fun cidIsStableForEqualDocumentsAndMovesWithTextRoleOrLineage() {
        val a = PromptDocument("hello", "Say hello.", tags = listOf("t"))
        val b = PromptDocument("hello", "Say hello.", tags = listOf("t"))
        assertEquals(a.cid, b.cid)
        assertNotEquals(a.cid, a.copy(text = "Say hi.").cid)
        assertNotEquals(a.cid, a.copy(role = PromptDocument.ROLE_SYSTEM).cid)
        assertNotEquals(a.cid, a.copy(previousCid = b.cid).cid)
        assertTrue(a.cid.startsWith("sha256:"))
    }

    @Test
    fun canonicalBytesRoundTripThroughFromJson() {
        val doc = PromptDocument("summarize", "Summarise {{doc}} in three sentences.", role = "system", tags = listOf("preset-corpus"), previousCid = null)
        val back = PromptDocument.fromJson(doc.canonicalJson())
        assertEquals(doc, back)
        assertEquals(doc.cid, back.cid)
        assertFailsWith<IllegalArgumentException> { PromptDocument.fromJson("""{"kind":"not-a-prompt","name":"x"}""") }
    }

    @Test
    fun namesObeyThePanelGrammar() {
        assertTrue(PromptDocument.isValidName("hello"))
        assertTrue(PromptDocument.isValidName("corpus.summarize-v2"))
        assertTrue(!PromptDocument.isValidName("Hello"))
        assertTrue(!PromptDocument.isValidName("-lead"))
        assertTrue(!PromptDocument.isValidName("a b"))
    }
}
