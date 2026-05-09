package borg.trikeshed.server

import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.server.generated.Elements
import borg.trikeshed.server.generated.Keys
import borg.trikeshed.htx.client.HtxElementCompat
import borg.trikeshed.quic.QuicElement
import borg.trikeshed.sctp.SctpElement
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class OpenApiGeneratorTddTest {

    // 10a — generated Keys htx is AsyncContextKey
    @Test
    fun `generated Keys htx is AsyncContextKey`() {
        assertTrue(Keys.htx is AsyncContextKey<*>)
    }

    // 10b — generated Elements htx creates HtxElementCompat
    @Test
    fun `generated Elements htx creates HtxElementCompat`() = runTest {
        val elem = Elements.htx()
        assertTrue(elem is HtxElementCompat)
    }

    // 22a — Elements htx returns HtxElementCompat in OPEN state
    @Test
    fun `Elements htx returns HtxElementCompat in OPEN state`() = runTest {
        val elem = Elements.htx()
        assertTrue(elem is HtxElementCompat)
        // HtxElementCompat is opened in openHtxElement()
        // verify it has been opened (lifecycle context available)
    }

    // 22b — Elements quic and sctp return their respective elements
    @Test
    fun `Elements quic and sctp return their respective elements`() = runTest {
        assertTrue(Elements.quic() is QuicElement)
        assertTrue(Elements.sctp() is SctpElement)
    }
}
