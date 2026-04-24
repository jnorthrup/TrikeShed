package borg.trikeshed.parse.kursive

import borg.trikeshed.lib.toSeries
import borg.trikeshed.lib.asString
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NarsiveDiag {
    @Test
    fun diagSimpleWord() {
        val r = Narsive.word.parse("bird")
        assertNotNull(r)
        assertEquals("bird", r.a.asString())
    }

    @Test
    fun diagSimpleCopula() {
        val r = Narsive.copula.parse("-->")
        assertNotNull(r)
        assertEquals("-->", r.a.asString())
    }

    @Test
    fun diagSimpleRelationship() {
        val r = Narsive.relationship.parse("<bird --> animal>")
        assertNotNull(r)
        // relationship should include 'bird' and the copula lexeme
        val lex = r.a.asString()
        assertTrue(lex.contains("bird"))
        assertTrue(lex.contains("-->"))
    }
}
