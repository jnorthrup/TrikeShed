package org.trikeshed.jules.probe

import borg.trikeshed.lib.get
import borg.trikeshed.lib.size

import borg.trikeshed.lib.emptySeriesOf
import kotlin.test.Test
import kotlin.test.assertEquals

class JulesProbeTest {
    @Test
    fun decode_test() {
        val handle = ProbeHandle("id", emptySeriesOf())
        val parsed = handle.decode("""{"id": "id", "metrics": [{"timestamp": 123, "value": 1.0}]}""")
        assertEquals("id", parsed.id)
        assertEquals(1, parsed.metrics.size)
        assertEquals(123L, parsed.metrics[0].timestamp)
        assertEquals(1.0, parsed.metrics[0].value)
    }
}
