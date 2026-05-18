package borg.trikeshed.parse.narsive

import borg.trikeshed.parse.kursive.parse
import kotlin.test.Test
import kotlin.test.assertNotNull

class NarsiveDiag {
    // 8a — simple word
    @Test
    fun `word parse returns bird`() {
        val r = Narsive.word.parse("bird")
        assertNotNull(r)
    }

    // 8b — copula parse
    @Test
    fun `copula parse returns implication`() {
        val r = Narsive.copula.parse("-->")
        assertNotNull(r)
    }
}
