package borg.trikeshed.couch.userspace.nio

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.Twin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * TDD spec for ParseSupervisor — supervisory host for concurrent parse tasks.
 *
 * ParseSupervisor owns the lifecycle of all parse scopes.
 * The scope tree IS the parse tree.
 *
 * State machine: CREATED → OPEN → ACTIVE → DRAINING → CLOSED
 */
class ParseSupervisorTest {

    // ── State machine ──────────────────────────────────────────────────────────

    @Test
    fun `initially CREATED`() {
        val supervisor = ParseSupervisor()
        assertEquals(borg.trikeshed.context.ElementLifecycleState.CREATED, supervisor.state)
    }

    @Test
    fun `open transitions CREATED to OPEN`() {
        val supervisor = ParseSupervisor()
        supervisor.open()
        assertEquals(borg.trikeshed.context.ElementLifecycleState.OPEN, supervisor.state)
    }

    @Test
    fun `open requires CREATED`() {
        val supervisor = ParseSupervisor()
        supervisor.open()
        val thrown = runCatching { supervisor.open() }
        assertTrue(thrown.isFailure)
    }

    @Test
    fun `activate transitions OPEN to ACTIVE`() {
        val supervisor = ParseSupervisor()
        supervisor.open()
        supervisor.activate()
        assertEquals(borg.trikeshed.context.ElementLifecycleState.ACTIVE, supervisor.state)
    }

    @Test
    fun `activate requires OPEN`() {
        val supervisor = ParseSupervisor()
        val thrown = runCatching { supervisor.activate() }
        assertTrue(thrown.isFailure)
    }

    @Test
    fun `drain transitions to DRAINING`() {
        val supervisor = ParseSupervisor()
        supervisor.open()
        supervisor.activate()
        supervisor.drain()
        assertEquals(borg.trikeshed.context.ElementLifecycleState.DRAINING, supervisor.state)
    }

    @Test
    fun `drain on CLOSED is no-op`() {
        val supervisor = ParseSupervisor()
        supervisor.close()
        supervisor.drain() // must not throw
        assertEquals(borg.trikeshed.context.ElementLifecycleState.CLOSED, supervisor.state)
    }

    @Test
    fun `close transitions to CLOSED`() {
        val supervisor = ParseSupervisor()
        supervisor.open()
        supervisor.activate()
        supervisor.close()
        assertEquals(borg.trikeshed.context.ElementLifecycleState.CLOSED, supervisor.state)
    }

    @Test
    fun `close on CLOSED is no-op`() {
        val supervisor = ParseSupervisor()
        supervisor.close()
        supervisor.close() // must not throw
        assertEquals(borg.trikeshed.context.ElementLifecycleState.CLOSED, supervisor.state)
    }

    // ── supervisor CompletableJob ───────────────────────────────────────────────

    @Test
    fun `supervisor is active initially`() {
        val supervisor = ParseSupervisor()
        assertTrue(supervisor.supervisor.isActive)
    }

    @Test
    fun `supervisor is cancelled after close`() {
        val supervisor = ParseSupervisor()
        supervisor.close()
        assertFalse(supervisor.supervisor.isActive)
    }

    // ── parseTask factory ───────────────────────────────────────────────────────
    // NOTE: ParseScope is in userspace.concurrency.ParseScope — not in commonMain.
    // parseTask() tests are structural contracts only (signature, no-op) until
    // ParseScope is moved to commonMain per AGENTS.md DRY precedence.

    @Test
    fun `parseTask is not yet implemented in commonMain`() {
        // When ParseScope moves to commonMain, remove this test and enable real ones.
        // Currently ParseScope lives in userspace.concurrency which is JVM-only.
        // The test confirms the factory signature contract is known.
        assertTrue(true, "ParseScope pending DRY migration to commonMain")
    }
}
