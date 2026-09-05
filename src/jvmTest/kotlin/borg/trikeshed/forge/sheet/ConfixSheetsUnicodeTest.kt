package borg.trikeshed.forge.sheet

import borg.trikeshed.parse.confix.confixDoc
import borg.trikeshed.parse.json.JsonSupport
import kotlin.test.Test
import kotlin.test.assertEquals

/** Narsese receipts carry « » and … ; the sheet family of such a fact must keep its shape. */
class ConfixSheetsUnicodeTest {
    private fun rowsOf(json: String): List<List<Any?>> = confixSheets("f", "f", confixDoc(json)).first().rows

    @Test
    fun rawUtf8() {
        val json = JsonSupport.stringify(mapOf("event" to "minted", "expression" to "«a … b» ==> «c»", "expectation" to 0.75, "n" to 7))
        assertEquals(listOf("event", "expression", "expectation", "n"), rowsOf(json).map { it[0] })
        assertEquals("«a … b» ==> «c»", rowsOf(json)[1][1], "UTF-8 text reifies as the text, not one char per byte")
    }

    @Test
    fun escapedQuotesInsideStrings() {
        val json = JsonSupport.stringify(mapOf("expression" to "say \"hi\" now", "b" to 1, "c" to "x"))
        assertEquals(listOf("expression", "b", "c"), rowsOf(json).map { it[0] })
        assertEquals("say \"hi\" now", rowsOf(json)[0][1])
    }

    @Test
    fun asciiEscaped() {
        val json = JsonSupport.stringify(mapOf("event" to "minted", "expression" to "«a … b» ==> «c»", "expectation" to 0.75, "n" to 7))
        val ascii = buildString { for (ch in json) if (ch.code < 0x80) append(ch) else append("\\u%04x".format(ch.code)) }
        assertEquals(listOf("event", "expression", "expectation", "n"), rowsOf(ascii).map { it[0] })
        assertEquals("«a … b» ==> «c»", rowsOf(ascii)[1][1])
    }
}
