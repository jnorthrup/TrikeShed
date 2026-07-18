package borg.trikeshed.forge.window

import kotlin.test.Test
import kotlin.test.assertNotNull

class BrowserForgeWindowManagerTest {
    @Test
    fun testInitialization() {
        val wm = BrowserForgeWindowManager()
        assertNotNull(wm)
    }
}
