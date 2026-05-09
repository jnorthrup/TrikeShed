package borg.trikeshed.combined

import borg.trikeshed.context.ElementState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest

class CombinedClientAppTest {

    // 1a — arg parsing
    @Test
    fun `parses -c --save-not-found=true -d mydir`() {
        val app = CombinedClientApp(listOf("-c", "--save-not-found=true", "-d", "mydir"))
        assertTrue(app.switches.continueDownload)
        assertTrue(app.switches.saveNotFound)
        assertEquals("mydir", app.switches.dir)
    }

    // 1b — lifecycle guard
    @Test
    fun `startRpcSession throws when not open`() = runTest {
        val app = CombinedClientApp()
        assertFailsWith<IllegalStateException> { app.startRpcSession(Channel<Any>()) }
    }

    // 11a — arg parsing sets switches correctly
    @Test
    fun `arg parsing sets switches correctly`() {
        val app = CombinedClientApp(listOf("-c", "-d", "mydir"))
        assertTrue(app.switches.continueDownload)
        assertEquals("mydir", app.switches.dir)
    }

    // 11b — child open failure rolls back parent
    @Test
    fun `child open failure rolls back parent`() = runTest {
        val failing = object : CombinedClientElement() {
            override suspend fun open() { throw RuntimeException("fail") }
        }
        val app = CombinedClientApp(combinedClient = failing)
        assertFailsWith<RuntimeException> { app.open() }
        assertEquals(ElementState.CLOSED, app.lifecycleState)
    }
}
