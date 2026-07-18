package borg.trikeshed.forge.window

import kotlin.test.Test
import kotlin.test.assertNotNull

class NodeForgeWindowManagerTest {
    @Test
    fun testInitialization() {
        val wm = NodeForgeWindowManager()
        assertNotNull(wm)
    }
}
