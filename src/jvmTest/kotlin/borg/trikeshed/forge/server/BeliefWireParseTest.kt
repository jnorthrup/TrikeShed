package borg.trikeshed.forge.server

import borg.trikeshed.parse.reify

import kotlin.test.Test

/** Diagnostic: what shapes does reify hand a nested facts payload? */
class BeliefWireParseTest {
    @Test
    fun probeNestedFactsShapes() {
        val text = """{"turnSucceeded":true,"facts":[{"verb":"crumb_walk","ok":true,"context":"kotlin build task","object":"kotlin-gradle"},{"verb":"bag_recall","ok":true,"context":"memory probe"}]}"""
        val parsed = reify(text)
        println("PROBE top: ${parsed?.let { it::class.qualifiedName }}")
        val map = parsed as Map<*, *>
        for ((k, v) in map) println("PROBE key=$k valueType=${v?.let { it::class.qualifiedName }} value=$v")
        val facts = map["facts"]
        if (facts is List<*>) {
            for (f in facts) {
                println("PROBE elem type=${f?.let { it::class.qualifiedName }}")
                if (f is Map<*, *>) for ((k, v) in f) println("PROBE   $k -> ${v?.let { it::class.qualifiedName }} = $v")
            }
        } else {
            println("PROBE facts is NOT a List: $facts")
        }
    }
}
