package borg.trikeshed.couch.userspace.nio

import borg.trikeshed.couch.htx.HtxBlock
import kotlin.test.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking

/**
 * Tests for ReactorSupervisor lifecycle transitions.
 *
 * State machine:
 *   CREATED → OPEN → ACTIVE → DRAINING → CLOSED
 *
 * Transition rules:
 * - open() requires CREATED
 * - activate() requires OPEN
 * - drain() moves to DRAINING (idempotent from CLOSED)
 * - close() moves to CLOSED from any state (idempotent from CLOSED)
 * - launchBranch requires ACTIVE
 */
class ReactorSupervisorLifecycleTest {

    // ── happy path ──────────────────────────────────────────────────

    @Test
    fun `CREATED → OPEN → ACTIVE → DRAINING → CLOSED`() {
        val rs = ReactorSupervisor("test")

        assertEquals(ReactorSupervisor.ReactorState.CREATED, rs.state)
        rs.open()
        assertEquals(ReactorSupervisor.ReactorState.OPEN, rs.state)
        rs.activate()
        assertEquals(ReactorSupervisor.ReactorState.ACTIVE, rs.state)
        rs.drain()
        assertEquals(ReactorSupervisor.ReactorState.DRAINING, rs.state)
        rs.close()
        assertEquals(ReactorSupervisor.ReactorState.CLOSED, rs.state)
    }

    // ── invalid transitions ─────────────────────────────────────────

    @Test
    fun `open from non-CREATED throws`() {
        val rs = ReactorSupervisor("test")
        rs.open()
        assertFailsWith<IllegalStateException> { rs.open() }
    }

    @Test
    fun `activate from non-OPEN throws`() {
        val rs = ReactorSupervisor("test")
        assertFailsWith<IllegalStateException> { rs.activate() }

        rs.open()
        rs.activate()
        assertFailsWith<IllegalStateException> { rs.activate() }
    }

    // ── idempotency ─────────────────────────────────────────────────

    @Test
    fun `drain from CLOSED is no-op`() {
        val rs = ReactorSupervisor("test")
        rs.open()
        rs.activate()
        rs.close()
        assertEquals(ReactorSupervisor.ReactorState.CLOSED, rs.state)
        rs.drain() // should not throw
        assertEquals(ReactorSupervisor.ReactorState.CLOSED, rs.state)
    }

    @Test
    fun `close from CLOSED is no-op`() {
        val rs = ReactorSupervisor("test")
        rs.close()
        assertEquals(ReactorSupervisor.ReactorState.CLOSED, rs.state)
        rs.close() // should not throw
        assertEquals(ReactorSupervisor.ReactorState.CLOSED, rs.state)
    }

    @Test
    fun `close from any state works`() {
        val inits = listOf<(ReactorSupervisor) -> Unit>(
            { /* CREATED */ },
            { it.open() },
            { it.open(); it.activate() },
            { it.open(); it.activate(); it.drain() },
        )
        for (init in inits) {
            val rs = ReactorSupervisor("test")
            init(rs)
            rs.close()
            assertEquals(ReactorSupervisor.ReactorState.CLOSED, rs.state)
        }
    }

    // ── launchBranch guards ─────────────────────────────────────────

    @Test
    fun `launchBranch fails outside ACTIVE`() {
        val channel = Channel<HtxBlock>(1)

        // CREATED → fails
        assertFailsWith<IllegalStateException> {
            ReactorSupervisor("test").launchBranch("b1", channel) { }
        }

        // OPEN → fails
        val rs = ReactorSupervisor("test")
        rs.open()
        assertFailsWith<IllegalStateException> {
            rs.launchBranch("b2", channel) { }
        }
    }

    @Test
    fun `launchBranch succeeds in ACTIVE`() = runBlocking {
        val rs = ReactorSupervisor("test")
        rs.open()
        rs.activate()

        val channel = Channel<HtxBlock>(1)
        val job: Job = rs.launchBranch("worker", channel) {
            // empty work — just completes
        }
        assertNotNull(job)
        assertEquals(1, rs.branches.size)
        assertEquals("worker", rs.branch("worker")?.name)
    }

    // ── session context ──────────────────────────────────────────────

    @Test
    fun `withSessionContext creates and caches sessions`() = runBlocking {
        val rs = ReactorSupervisor("test")
        assertNull(rs.session("s1"))

        rs.withSessionContext("s1") {
            assertEquals("s1", sessionId)
        }

        assertNotNull(rs.session("s1"))
        assertEquals("s1", rs.session("s1")?.sessionId)
        assertEquals(1, rs.sessions.size)
    }

    @Test
    fun `withSessionContext reuses existing session`() = runBlocking {
        val rs = ReactorSupervisor("test")

        rs.withSessionContext("s1") { /* first visit */ }
        rs.withSessionContext("s1") { /* second visit */ }

        assertEquals(1, rs.sessions.size)
    }

    // ── context palette ──────────────────────────────────────────────

    @Test
    fun `withKey exposes key in contextPalette`() {
        val rs = ReactorSupervisor("test")
        val key = object : kotlin.coroutines.CoroutineContext.Key<kotlin.coroutines.CoroutineContext.Element> {}
        rs.withKey(key, "value1")

        val palette = rs.contextPalette
        assertTrue(key in palette)
        assertEquals("value1", palette[key])
    }

    // ── supervisor job lifecycle ────────────────────────────────────

    @Test
    fun `drain completes supervisor job`() {
        val rs = ReactorSupervisor("test")
        rs.open()
        rs.activate()
        assertFalse(rs.supervisor.isCompleted)

        rs.drain()
        assertTrue(rs.supervisor.isCompleted)
    }

    @Test
    fun `close completes supervisor job`() {
        val rs = ReactorSupervisor("test")
        assertFalse(rs.supervisor.isCompleted)

        rs.close()
        assertTrue(rs.supervisor.isCompleted)
    }

    // ── realm identity ───────────────────────────────────────────────

    @Test
    fun `realm is preserved`() {
        val rs = ReactorSupervisor("myrealm")
        assertEquals("myrealm", rs.realm)
    }

    @Test
    fun `default constructor uses ReactorSupervisorKey`() {
        val rs = ReactorSupervisor("default")
        assertEquals(ReactorSupervisorKey, rs.key)
    }

    // ── branch access ────────────────────────────────────────────────

    @Test
    fun `branch returns null for unknown name`() {
        val rs = ReactorSupervisor("test")
        assertNull(rs.branch("nonexistent"))
    }
}
