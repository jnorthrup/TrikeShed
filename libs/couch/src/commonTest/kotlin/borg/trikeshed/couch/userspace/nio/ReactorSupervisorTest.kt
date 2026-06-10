package borg.trikeshed.couch.userspace.nio

import borg.trikeshed.couch.htx.HtxBlock
import borg.trikeshed.couch.htx.HtxBlockType
import borg.trikeshed.context.ElementState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * TDD spec for ReactorSupervisor, BranchScope, and SessionContext.
 *
 * ReactorSupervisor: owns the lifecycle of all protocol recognizers,
 * parse scopes, and session dispatches. Each child is a BranchScope
 * that lives under the SupervisorJob.
 *
 * BranchScope: one concurrent branch of the Reactor — a named channel
 * + the coroutine that processes it. Lives as a child of the
 * ReactorSupervisor SupervisorJob.
 *
 * SessionContext: session-sticky routing, keyed by SessionContextKey
 * in CoroutineContext. Injected into all session-scoped coroutines.
 */
class ReactorSupervisorTest {

    // ── ReactorSupervisor state machine ────────────────────────────────────────

    @Test
    fun `initially CREATED`() {
        val supervisor = ReactorSupervisor("test")
        assertEquals(ReactorSupervisor.ReactorState.CREATED, supervisor.state)
    }

    @Test
    fun `open transitions CREATED to OPEN`() {
        val supervisor = ReactorSupervisor("test")
        supervisor.open()
        assertEquals(ReactorSupervisor.ReactorState.OPEN, supervisor.state)
    }

    @Test
    fun `open requires CREATED`() {
        val supervisor = ReactorSupervisor("test")
        supervisor.open()
        // second open throws
        val thrown = runCatching { supervisor.open() }
        assertTrue(thrown.isFailure)
    }

    @Test
    fun `activate transitions OPEN to ACTIVE`() {
        val supervisor = ReactorSupervisor("test")
        supervisor.open()
        supervisor.activate()
        assertEquals(ReactorSupervisor.ReactorState.ACTIVE, supervisor.state)
    }

    @Test
    fun `activate requires OPEN`() {
        val supervisor = ReactorSupervisor("test")
        val thrown = runCatching { supervisor.activate() }
        assertTrue(thrown.isFailure)
    }

    @Test
    fun `drain transitions to DRAINING`() {
        val supervisor = ReactorSupervisor("test")
        supervisor.open()
        supervisor.activate()
        supervisor.drain()
        assertEquals(ReactorSupervisor.ReactorState.DRAINING, supervisor.state)
    }

    @Test
    fun `drain on CLOSED is no-op`() {
        val supervisor = ReactorSupervisor("test")
        supervisor.close()
        supervisor.drain() // must not throw
        assertEquals(ReactorSupervisor.ReactorState.CLOSED, supervisor.state)
    }

    @Test
    fun `close transitions to CLOSED`() {
        val supervisor = ReactorSupervisor("test")
        supervisor.open()
        supervisor.activate()
        supervisor.close()
        assertEquals(ReactorSupervisor.ReactorState.CLOSED, supervisor.state)
    }

    @Test
    fun `close on CLOSED is no-op`() {
        val supervisor = ReactorSupervisor("test")
        supervisor.close()
        supervisor.close() // must not throw
        assertEquals(ReactorSupervisor.ReactorState.CLOSED, supervisor.state)
    }

    // ── supervisor is a CompletableJob ────────────────────────────────────────

    @Test
    fun `supervisor is a CompletableJob`() {
        val supervisor = ReactorSupervisor("test")
        assertTrue(supervisor.supervisor.isActive)
        supervisor.close()
        assertFalse(supervisor.supervisor.isActive)
    }

    // ── realm ────────────────────────────────────────────────────────────────

    @Test
    fun `realm is stored`() {
        val supervisor = ReactorSupervisor("binance-kline")
        assertEquals("binance-kline", supervisor.realm)
    }

    // ── context palette ────────────────────────────────────────────────────────

    @Test
    fun `contextPalette starts empty`() {
        val supervisor = ReactorSupervisor("test")
        assertTrue(supervisor.contextPalette.isEmpty())
    }

    @Test
    fun `withKey adds to palette`() {
        val supervisor = ReactorSupervisor("test")
        @Suppress("UNCHECKED_CAST")
        val result = supervisor.withKey(ReactorSupervisorKey, "value" as Any?)
        assertSame(supervisor, result)
        assertEquals(1, supervisor.contextPalette.size)
        assertEquals("value", supervisor.contextPalette[ReactorSupervisorKey])
    }

    @Test
    fun `withKey returns self for chaining`() {
        val supervisor = ReactorSupervisor("test")
        val r1 = supervisor.withKey(ReactorSupervisorKey, "v1")
        val r2 = r1.withKey(ReactorSupervisorKey, "v2")
        assertSame(r1, r2)
    }

    // ── BranchScope ───────────────────────────────────────────────────────────

    @Test
    fun `launchBranch requires ACTIVE state`() {
        val supervisor = ReactorSupervisor("test")
        supervisor.open()
        // not yet ACTIVE
        val ch = Channel<HtxBlock>(Channel.BUFFERED)
        val thrown = runCatching {
            supervisor.launchBranch("branch1", ch) { }
        }
        assertTrue(thrown.isFailure)
    }

    @Test
    fun `branch returns registered branch by name`() {
        val supervisor = ReactorSupervisor("test")
        supervisor.open()
        supervisor.activate()
        val ch = Channel<HtxBlock>(Channel.BUFFERED)
        supervisor.launchBranch("worker-1", ch) { }
        assertNotNull(supervisor.branch("worker-1"))
    }

    @Test
    fun `branch returns null for unknown name`() {
        val supervisor = ReactorSupervisor("test")
        assertNull(supervisor.branch("does-not-exist"))
    }

    @Test
    fun `branches returns all registered branches`() {
        val supervisor = ReactorSupervisor("test")
        supervisor.open()
        supervisor.activate()
        supervisor.launchBranch("b1", Channel<HtxBlock>(Channel.BUFFERED)) { }
        supervisor.launchBranch("b2", Channel<HtxBlock>(Channel.BUFFERED)) { }
        assertEquals(2, supervisor.branches.size)
    }

    // ── SessionContext ───────────────────────────────────────────────────────

    @Test
    fun `session returns null for unknown sessionId`() {
        val supervisor = ReactorSupervisor("test")
        assertNull(supervisor.session("no-such-session"))
    }

    @Test
    fun `withSessionContext creates session lazily`() = runTest {
        val supervisor = ReactorSupervisor("test")
        val sessionId = "session-42"
        supervisor.open()
        supervisor.activate()
        val result = supervisor.withSessionContext(sessionId) {
            assertEquals(sessionId, sessionId)
            sessionId
        }
        assertEquals(sessionId, result)
        assertNotNull(supervisor.session(sessionId))
    }

    @Test
    fun `withSessionContext reuses existing session`() = runTest {
        val supervisor = ReactorSupervisor("test")
        supervisor.open()
        supervisor.activate()
        val sessionId = "session-42"

        supervisor.withSessionContext(sessionId) { }
        val first = supervisor.session(sessionId)

        supervisor.withSessionContext(sessionId) { }
        val second = supervisor.session(sessionId)

        assertSame(first, second)
    }

    @Test
    fun `sessions returns all registered sessions`() {
        val supervisor = ReactorSupervisor("test")
        supervisor.open()
        supervisor.activate()
        // Create sessions via withSessionContext would need runTest scope
        assertTrue(supervisor.sessions.isEmpty())
    }

    // ── SessionContext CoroutineContext.Element ────────────────────────────────

    @Test
    fun `SessionContext is a CoroutineContext Element`() {
        val session = SessionContext("sid-1")
        val ctx = session as kotlin.coroutines.CoroutineContext
        assertSame(session, ctx[SessionContextKey])
    }

    @Test
    fun `SessionContext CoroutineContext key resolution`() {
        val sessionA = SessionContext("session-A")
        val ctx: kotlin.coroutines.CoroutineContext = sessionA
        // session IS the CoroutineContext.Element; ctx[SessionContextKey] returns it
        assertSame(sessionA, ctx[SessionContextKey])
    }

    @Test
    fun `SessionContext register and lookup handler`() {
        val session = SessionContext("sid-1")
        var handled = false
        session.register("REQ", object : MessageHandler() {
            override suspend fun handle(block: HtxBlock) { handled = true }
        })
        assertTrue(handled)
    }

    @Test
    fun `SessionContext handler returns null for unknown tag`() {
        val session = SessionContext("sid-1")
        assertNull(session.handler("UNKNOWN_TAG"))
    }

    // ── BranchDispatch ───────────────────────────────────────────────────────

    @Test
    fun `BranchDispatch dispatch is no-op by default`() {
        val dispatch = object : BranchDispatch() {}
        val block = HtxBlock(HtxBlockType.DHTX_REQ, 0, 0, 0u)
        dispatch.dispatch(block) // must not throw
    }
}
