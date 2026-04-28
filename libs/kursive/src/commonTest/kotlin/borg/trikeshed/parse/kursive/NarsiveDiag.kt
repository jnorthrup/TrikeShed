package borg.trikeshed.parse.kursive

import borg.trikeshed.lib.CharSeries
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.s
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
        val r: Join<CharSeries, NarsiveTrace>? = Narsive.copula.parse("-->")
        assertNotNull(r)
        assertEquals("-->", r.a.asString())
    }

    @Test
    fun diagSimpleRelationship() {
        val r: Join<CharSeries, NarsiveTrace>? = Narsive.relationship.parse("(bird --> animal)")
        assertNotNull(r)
        // relationship should include 'bird' and the copula lexeme
        val lex: String = r.a.s
        assertTrue(lex.contains("bird"))
        assertTrue(lex.contains("-->"))
    }
}
