package borg.trikeshed.graph.query

import borg.trikeshed.lib.*
import kotlin.test.Test

class TestGet {
    @Test
    fun test() {
        val s: Series<Int> = 1 j { 1 }
        println("b: " + s.b(0))
        println("get: " + s.get(0))
    }
}
