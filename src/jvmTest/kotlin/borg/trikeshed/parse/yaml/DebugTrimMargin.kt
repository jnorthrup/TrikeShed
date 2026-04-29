@file:Suppress("unused")
package borg.trikeshed.parse.yaml

import kotlin.test.Test
import kotlin.test.*

class DebugTrimMargin {
    @Test
    fun debugTrim() {
        val s = """
           |openapi: 3.1.0
           |info:
           |  title: Example API
           |""".trimMargin( )
        // trimMargin( ) passes a single space as marginPrefix
        // Let's see what we get
        val lines = s.lines()
        for ((i, line) in lines.withIndex()) {
            assertEquals(-1, line.indexOf('|'), "line $i should not contain |: [$line]")
        }
    }
}
