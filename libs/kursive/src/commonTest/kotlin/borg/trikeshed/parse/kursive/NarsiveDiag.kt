package borg.trikeshed.parse.kursive

import borg.trikeshed.lib.toSeries
import kotlin.test.Test
import kotlin.test.assertNotNull

class NarsiveDiag {
    @Test
    fun diagSimpleWord() {
        val r = Narsive.word.parse("bird")
        println("word parse: $r")
    }
    @Test
    fun diagSimpleCopula() {
        val r = Narsive.copula.parse("-->")
        println("copula parse: $r")
    }
    @Test
    fun diagSimpleRelationship() {
        val r = Narsive.relationship.parse("<bird --> animal>")
        println("relationship parse: $r")
    }
}
