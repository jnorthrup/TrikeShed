package borg.trikeshed.combined

import borg.trikeshed.context.ElementState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest

class CombinedClientAppTest {

    @Test
    fun testArgParsing() {
        val app1 = CombinedClientApp(listOf("-c", "--save-not-found=true", "-d", "mydir"))
        assertTrue(app1.switches.continueDownload)
        assertTrue(app1.switches.saveNotFound)
        assertEquals("mydir", app1.switches.dir)

        val app2 = CombinedClientApp(listOf("-d", "-c"))
        assertTrue(app2.switches.continueDownload)
        assertEquals(null, app2.switches.dir)

        val app3 = CombinedClientApp(listOf("-d"))
        assertEquals(null, app3.switches.dir)
    }

    @Test
    fun testSessionLifecycle() = runTest {
        val app = CombinedClientApp()
        app.open()
        assertEquals(ElementState.OPEN, app.lifecycleState)

        val channel = Channel<String>()
        val job = app.startRpcSession(channel)

        assertTrue(job.isActive)

        channel.send("unknowncommand")

        channel.close()
        job.join()
        assertTrue(job.isCompleted)

        app.close()
        assertEquals(ElementState.CLOSED, app.lifecycleState)
    }

    @Test
    fun testRpcSessionFailsIfNotOpen() = runTest {
        val app = CombinedClientApp()
        val channel = Channel<String>()
        assertFailsWith<IllegalStateException> {
            app.startRpcSession(channel)
        }
    }

    @Test
    fun testChildOpenFailureRollback() = runTest {
        val failingClient = object : CombinedClientElement() {
            override suspend fun open() {
                throw RuntimeException("Simulated failure")
            }
        }
        val app = CombinedClientApp(combinedClient = failingClient)

        val ex = assertFailsWith<RuntimeException> {
            app.open()
        }
        assertEquals("Simulated failure", ex.message)
        assertEquals(ElementState.CLOSED, app.lifecycleState)
    }
}
