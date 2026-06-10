package org.xvm.cursor

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.xvm.runtime.XvmLifecycle

class XvmLifecycleTest {

    // ── state() accessor — 2 tests per return path ──────────────────────

    @Test
    fun `state returns INIT on construction`() {
        val lc = XvmLifecycle()
        assertEquals(XvmLifecycle.State.INIT, lc.state())
    }

    @Test
    fun `state returns INIT before any transition`() {
        val lc = XvmLifecycle()
        assertFalse(lc.isRunning)
        assertEquals(XvmLifecycle.State.INIT, lc.state())
    }

    @Test
    fun `state returns RUNNING after start`() {
        val lc = XvmLifecycle()
        lc.start()
        assertEquals(XvmLifecycle.State.RUNNING, lc.state())
    }

    @Test
    fun `state returns RUNNING mid-lifecycle`() {
        val lc = XvmLifecycle()
        lc.start()
        assertTrue(lc.isRunning)
        assertEquals(XvmLifecycle.State.RUNNING, lc.state())
    }

    @Test
    fun `state returns DRAINING after drain`() {
        val lc = XvmLifecycle()
        lc.start()
        lc.drain()
        assertEquals(XvmLifecycle.State.DRAINING, lc.state())
    }

    @Test
    fun `state returns DRAINING before shutdown`() {
        val lc = XvmLifecycle()
        lc.start()
        lc.drain()
        assertFalse(lc.isShutdown)
        assertEquals(XvmLifecycle.State.DRAINING, lc.state())
    }

    @Test
    fun `state returns SHUTDOWN after shutdown`() {
        val lc = XvmLifecycle()
        lc.start()
        lc.drain()
        lc.shutdown()
        assertEquals(XvmLifecycle.State.SHUTDOWN, lc.state())
    }

    @Test
    fun `state returns SHUTDOWN at terminal`() {
        val lc = XvmLifecycle()
        lc.start()
        lc.drain()
        lc.shutdown()
        assertTrue(lc.isShutdown)
        assertEquals(XvmLifecycle.State.SHUTDOWN, lc.state())
    }

    // ── isRunning — 2 true, 2 false ────────────────────────────────────

    @Test
    fun `isRunning true after start`() {
        val lc = XvmLifecycle()
        lc.start()
        assertTrue(lc.isRunning)
    }

    @Test
    fun `isRunning true before drain`() {
        val lc = XvmLifecycle()
        lc.start()
        assertFalse(lc.isDraining)
        assertTrue(lc.isRunning)
    }

    @Test
    fun `isRunning false in INIT`() {
        assertFalse(XvmLifecycle().isRunning)
    }

    @Test
    fun `isRunning false in DRAINING`() {
        val lc = XvmLifecycle()
        lc.start()
        lc.drain()
        assertFalse(lc.isRunning)
    }

    // ── isDraining — 2 true, 2 false ───────────────────────────────────

    @Test
    fun `isDraining true after drain`() {
        val lc = XvmLifecycle()
        lc.start()
        lc.drain()
        assertTrue(lc.isDraining)
    }

    @Test
    fun `isDraining true before shutdown`() {
        val lc = XvmLifecycle()
        lc.start()
        lc.drain()
        assertFalse(lc.isShutdown)
        assertTrue(lc.isDraining)
    }

    @Test
    fun `isDraining false in INIT`() {
        assertFalse(XvmLifecycle().isDraining)
    }

    @Test
    fun `isDraining false in RUNNING`() {
        val lc = XvmLifecycle()
        lc.start()
        assertFalse(lc.isDraining)
    }

    // ── isShutdown — 2 true, 2 false ───────────────────────────────────

    @Test
    fun `isShutdown true after shutdown`() {
        val lc = XvmLifecycle()
        lc.start()
        lc.drain()
        lc.shutdown()
        assertTrue(lc.isShutdown)
    }

    @Test
    fun `isShutdown true at terminal`() {
        val lc = XvmLifecycle()
        lc.start()
        lc.drain()
        lc.shutdown()
        assertFalse(lc.isRunning)
        assertTrue(lc.isShutdown)
    }

    @Test
    fun `isShutdown false in INIT`() {
        assertFalse(XvmLifecycle().isShutdown)
    }

    @Test
    fun `isShutdown false in RUNNING`() {
        val lc = XvmLifecycle()
        lc.start()
        assertFalse(lc.isShutdown)
    }

    // ── start() guard — 2 per invalid source state ─────────────────────

    @Test
    fun `start throws from DRAINING`() {
        val lc = XvmLifecycle()
        lc.start()
        lc.drain()
        assertThrows<IllegalStateException> { lc.start() }
    }

    @Test
    fun `start throws from SHUTDOWN`() {
        val lc = XvmLifecycle()
        lc.start()
        lc.drain()
        lc.shutdown()
        assertThrows<IllegalStateException> { lc.start() }
    }

    @Test
    fun `start double-call throws from RUNNING`() {
        val lc = XvmLifecycle()
        lc.start()
        assertThrows<IllegalStateException> { lc.start() }
    }

    // ── drain() guard — 2 per invalid source state ─────────────────────

    @Test
    fun `drain throws from INIT`() {
        assertThrows<IllegalStateException> { XvmLifecycle().drain() }
    }

    @Test
    fun `drain throws from DRAINING`() {
        val lc = XvmLifecycle()
        lc.start()
        lc.drain()
        assertThrows<IllegalStateException> { lc.drain() }
    }

    @Test
    fun `drain throws from SHUTDOWN`() {
        val lc = XvmLifecycle()
        lc.start()
        lc.drain()
        lc.shutdown()
        assertThrows<IllegalStateException> { lc.drain() }
    }

    // ── shutdown() guard — 2 per invalid source state ──────────────────

    @Test
    fun `shutdown throws from INIT`() {
        assertThrows<IllegalStateException> { XvmLifecycle().shutdown() }
    }

    @Test
    fun `shutdown throws from RUNNING`() {
        val lc = XvmLifecycle()
        lc.start()
        assertThrows<IllegalStateException> { lc.shutdown() }
    }

    @Test
    fun `shutdown throws from SHUTDOWN`() {
        val lc = XvmLifecycle()
        lc.start()
        lc.drain()
        lc.shutdown()
        assertThrows<IllegalStateException> { lc.shutdown() }
    }

    // ── full lifecycle integration ──────────────────────────────────────

    @Test
    fun `full lifecycle INIT to SHUTDOWN`() {
        val lc = XvmLifecycle()
        assertEquals(XvmLifecycle.State.INIT, lc.state())
        lc.start()
        assertTrue(lc.isRunning)
        lc.drain()
        assertTrue(lc.isDraining)
        lc.shutdown()
        assertTrue(lc.isShutdown)
    }
}
