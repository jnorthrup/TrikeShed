package borg.trikeshed.forge.window

import kotlin.test.Test
import kotlin.test.assertNotNull

class AndroidForgeWindowManagerTest {
    @Test
    fun testInitialization() {
        val wm = AndroidForgeWindowManager()
        assertNotNull(wm)
    }
}
