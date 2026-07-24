package borg.trikeshed.daemon

import kotlin.test.Test
import kotlin.test.assertEquals

class OroborosDaemonArgsTest {

    @Test
    fun testDefaultIntervalMs() {
        val args = emptyArray<String>()
        val config = OroborosDaemon.parseConfig(args)
        assertEquals(30_000L, config.intervalMs)
        assertEquals(OroborosDaemon.DEFAULT_INTERVAL_MS, config.intervalMs)
    }

    @Test
    fun testOverrideIntervalMs() {
        val args = arrayOf("--interval-ms", "5000")
        val config = OroborosDaemon.parseConfig(args)
        assertEquals(5000L, config.intervalMs)
    }
}
