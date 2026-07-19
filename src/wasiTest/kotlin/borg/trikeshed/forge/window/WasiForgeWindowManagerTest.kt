package borg.trikeshed.forge.window

import kotlin.test.Test
import kotlin.test.assertNotNull

class WasiForgeWindowManagerTest {
    @Test
    fun testInitialization() {
        val wm = WasiForgeWindowManager()
        assertNotNull(wm)
    }
}
