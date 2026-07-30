package org.trikeshed.jules.probe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JulesProbeTest {
    @Test
    fun decode_test() {
        val handle = ProbeHandle("id", object : Series<ProbeMetric> {
            override val size: Int = 0
            override fun get(index: Int): ProbeMetric = throw IndexOutOfBoundsException()
        })
        val parsed = handle.decode("""{"id": "id", "metrics": [{"timestamp": 123, "value": 1.0}]}""")
        assertEquals("id", parsed.id)
        assertEquals(1, parsed.metrics.size)
        assertEquals(123L, parsed.metrics[0].timestamp)
        assertEquals(1.0, parsed.metrics[0].value)
    }
}
