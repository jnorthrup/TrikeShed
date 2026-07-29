package org.trikeshed.jules.probe

import kotlin.test.Test
import kotlin.test.assertFailsWith

class JulesProbeTest {
    @Test
    fun decode_fails_with_todo() {
        val handle = ProbeHandle("id", object : Series<ProbeMetric> {
            override val size: Int = 0
            override fun get(index: Int): ProbeMetric = throw IndexOutOfBoundsException()
        })

        assertFailsWith<NotImplementedError> {
            handle.decode("{}")
        }
    }
}
