package borg.trikeshed.forge.window

import kotlin.test.Test
import kotlin.test.assertNotNull

class NativeForgeWindowManagerTest {
    @Test
    fun testInitialization() {
        val wm = NativeForgeWindowManager()
        assertNotNull(wm)
    }
}
