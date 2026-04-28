package borg.trikeshed.server

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * TDD spec for server OpenAPI-generated shapes and buildServerContext/closeServerContext.
 */
class OpenApiGeneratorTddTest {

    // ── buildServerContext ──────────────────────────────────────────────────────

    @Test
    fun `buildServerContext creates QuicElement, SctpElement, HtxElement`() = runTest {
        val ctx = buildServerContext()
        assertNotNull(ctx[borg.trikeshed.quic.QuicKey])
        assertNotNull(ctx[borg.trikeshed.sctp.SctpKey])
        assertNotNull(ctx[borg.trikeshed.htx.client.HtxKey])
    }

    @Test
    fun `buildServerContext elements are OPEN`() = runTest {
        val ctx = buildServerContext()
        val htx = ctx[borg.trikeshed.htx.client.HtxKey]
        assertEquals(ElementState.OPEN, (htx as AsyncContextElement).state)
    }

    @Test
    fun `closeServerContext closes open elements`() = runTest {
        val ctx = buildServerContext()
        closeServerContext(ctx)
        val htx = ctx[borg.trikeshed.htx.client.HtxKey] as AsyncContextElement
        assertEquals(ElementState.CLOSED, htx.state)
    }

    @Test
    fun `closeServerContext is idempotent`() = runTest {
        val ctx = buildServerContext()
        closeServerContext(ctx)
        closeServerContext(ctx) // must not throw
    }

    // ── Generated Keys ───────────────────────────────────────────────────────

    @Test
    fun `generated Keys has htx quic sctp keys`() {
        val keys = borg.trikeshed.server.generated.Keys
        assertTrue(keys.htx is AsyncContextKey<*>)
        assertTrue(keys.quic is AsyncContextKey<*>)
        assertTrue(keys.sctp is AsyncContextKey<*>)
    }

    @Test
    fun `generated Keys htx resolves from server context`() = runTest {
        val ctx = buildServerContext()
        val htxKey = borg.trikeshed.server.generated.Keys.htx
        assertSame(ctx[borg.trikeshed.htx.client.HtxKey], ctx[htxKey])
    }

    @Test
    fun `generated Keys quic resolves from server context`() = runTest {
        val ctx = buildServerContext()
        val quicKey = borg.trikeshed.server.generated.Keys.quic
        assertSame(ctx[borg.trikeshed.quic.QuicKey], ctx[quicKey])
    }

    @Test
    fun `generated Keys sctp resolves from server context`() = runTest {
        val ctx = buildServerContext()
        val sctpKey = borg.trikeshed.server.generated.Keys.sctp
        assertSame(ctx[borg.trikeshed.sctp.SctpKey], ctx[sctpKey])
    }

    // ── Generated Elements ───────────────────────────────────────────────────

    @Test
    fun `generated Elements htx creates HtxElement in OPEN state`() = runTest {
        val elem = borg.trikeshed.server.generated.Elements.htx()
        assertTrue(elem is borg.trikeshed.htx.client.HtxElement)
        assertEquals(ElementState.OPEN, elem.state)
    }

    // ── Generated SupervisorJobs ─────────────────────────────────────────────

    @Test
    fun `generated SupervisorJobs getHealth returns Job`() {
        val job = borg.trikeshed.server.generated.SupervisorJobs.getHealth()
        assertNotNull(job)
    }

    @Test
    fun `getHealth accepts null parent`() {
        val job = borg.trikeshed.server.generated.SupervisorJobs.getHealth(null)
        assertNotNull(job)
    }
}
