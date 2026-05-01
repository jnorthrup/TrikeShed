package borg.trikeshed.htx.client

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * TDD spec for HtxElement lifecycle, key encapsulation, and request dispatch.
 */
class HtxElementTddTest {

    // ── HtxElement is an AsyncContextElement ──────────────────────────────────

    @Test
    fun `HtxElement implements AsyncContextElement`() {
        val elem = HtxElement()
        assertTrue(elem is AsyncContextElement)
    }

    @Test
    fun `HtxElement key returns HtxKey companion singleton`() {
        val elem = HtxElement()
        assertSame(HtxKey, elem.key)
        assertSame(HtxElement.Key, elem.key)
    }

    // ── Lifecycle state machine ──────────────────────────────────────────────

    @Test
    fun `HtxElement starts CREATED`() {
        val elem = HtxElement()
        assertEquals(ElementState.CREATED, elem.state)
    }

    @Test
    fun `open transitions CREATED to OPEN`() = runTest {
        val elem = HtxElement()
        elem.open()
        assertEquals(ElementState.OPEN, elem.state)
    }

    @Test
    fun `open is idempotent`() = runTest {
        val elem = HtxElement()
        elem.open()
        elem.open()
        assertEquals(ElementState.OPEN, elem.state)
    }

    @Test
    fun `close transitions OPEN to CLOSED`() = runTest {
        val elem = HtxElement()
        elem.open()
        elem.close()
        assertEquals(ElementState.CLOSED, elem.state)
    }

    // ── openHtxElement factory ──────────────────────────────────────────────

    @Test
    fun `openHtxElement creates and opens element`() = runTest {
        val elem = openHtxElement()
        assertEquals(ElementState.OPEN, elem.state)
    }

    @Test
    fun `openHtxElement uses default handler when none provided`() = runTest {
        val elem = openHtxElement()
        val msg = elem.request("GET", "/health")
        assertEquals(200, msg.status)
        assertEquals("ok", msg.body)
    }

    @Test
    fun `openHtxElement uses custom handler`() = runTest {
        var called = false
        val elem = openHtxElement { req ->
            called = true
            HtxClientMessage(201, "custom")
        }
        val msg = elem.request("GET", "/anything")
        assertTrue(called)
        assertEquals(201, msg.status)
        assertEquals("custom", msg.body)
    }

    // ── request method ─────────────────────────────────────────────────────




    @Test
    fun `request normalizes method to uppercase`() = runTest {
        val elem = openHtxElement()
        val msg = elem.request("get", "/health")
        assertEquals(200, msg.status)
    }

    @Test
    fun `request rejects blank method with 400`() = runTest {
        val elem = openHtxElement()
        val msg = elem.request("", "/health")
        assertEquals(400, msg.status)
    }

    @Test
    fun `request rejects blank path with 400`() = runTest {
        val elem = openHtxElement()
        val msg = elem.request("GET", "  ")
        assertEquals(400, msg.status)
    }

    @Test
    fun `request returns 405 for wrong method on health endpoint`() = runTest {
        val elem = openHtxElement()
        val msg = elem.request("POST", "/health")
        assertEquals(405, msg.status)
    }

    @Test
    fun `request returns 404 for unknown path`() = runTest {
        val elem = openHtxElement()
        val msg = elem.request("GET", "/unknown")
        assertEquals(404, msg.status)
    }

    // ── request requires OPEN ──────────────────────────────────────────────

    @Test
    fun `request throws when not open`() = runTest {
        val elem = HtxElement()
        val thrown = runCatching { elem.request("GET", "/health") }
        assertTrue(thrown.isFailure)
    }

    // ── HtxKey is AsyncContextKey<HtxElement> ───────────────────────────────

    @Test
    fun `HtxKey is AsyncContextKey of HtxElement`() {
        assertTrue(HtxKey is AsyncContextKey<HtxElement>)
    }

    @Test
    fun `context lookup resolves HtxElement via HtxKey`() = runTest {
        val elem = openHtxElement()
        val ctx = elem as kotlin.coroutines.CoroutineContext
        assertSame(elem, ctx[HtxKey])
    }

    // ── Aria2Switches algebra ─────────────────────────────────────────────

    @Test
    fun `Aria2Switches renders -Z always`() {
        val sw = Aria2Switches()
        val args = sw.toArgs()
        assertTrue(args.contains("-Z"))
    }

    @Test
    fun `Aria2Switches renders -c only when continueDownload true`() {
        val withC = Aria2Switches(continueDownload = true).toArgs()
        val withoutC = Aria2Switches(continueDownload = false).toArgs()
        assertTrue(withC.contains("-c"))
        assertFalse(withoutC.contains("-c"))
    }

    @Test
    fun `Aria2Switches renders --save-not-found=false only when false`() {
        val args = Aria2Switches(saveNotFound = false).toArgs()
        assertTrue(args.contains("--save-not-found=false"))
    }

    @Test
    fun `Aria2Switches renders -x -j -s with correct values`() {
        val sw = Aria2Switches(maxConnectionsPerServer = 8, maxConcurrentDownloads = 4, split = 3)
        val args = sw.toArgs()
        assertTrue(args.contains("-x8"))
        assertTrue(args.contains("-j4"))
        assertTrue(args.contains("-s3"))
    }

    @Test
    fun `Aria2Switches renders -d when dir is set`() {
        val sw = Aria2Switches(dir = "/tmp/downloads")
        val args = sw.toArgs()
        assertTrue(args.indexOf("-d") < args.indexOf("/tmp/downloads"))
    }
}
