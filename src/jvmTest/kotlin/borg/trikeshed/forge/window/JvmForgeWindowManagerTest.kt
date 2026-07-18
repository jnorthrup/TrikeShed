package borg.trikeshed.forge.window

import kotlin.test.Test
import kotlin.test.assertNotNull

class JvmForgeWindowManagerTest {
    @Test
    fun testInitialization() {
        val wm = JvmForgeWindowManager()
        assertNotNull(wm)
    }
}
