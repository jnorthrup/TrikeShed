package borg.trikeshed.ccek

import borg.trikeshed.HomeDirService
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.quic.QuicElement
import borg.trikeshed.sctp.SctpElement
import borg.trikeshed.signal.IndicatorContextService
import borg.trikeshed.signal.SampleStrategySignals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class KeyedServiceTest {

    @Test
    fun baseComposition() = runTest {
        val ctx = HomeDirService("~") + QuicElement()
        withContext(ctx) {
            assertNotNull(coroutineContext[HomeDirService.Key], "HomeDirService must be present")
            assertNotNull(coroutineContext[QuicElement.Key], "QuicElement must be present")
        }
    }

    @Test
    fun absentServiceReturnsNull() = runTest {
        assertNull(coroutineContext[HomeDirService.Key], "HomeDirService absent from empty context")
    }

    @Test
    fun withContextRoundTrip() = runTest {
        val path = withContext(HomeDirService("/tmp")) {
            coroutineContext[HomeDirService.Key]!!.path
        }
        assertEquals("/tmp", path)
    }

    @Test
    fun transportCoexistence() = runTest {
        val ctx = SctpElement() + QuicElement()
        withContext(ctx) {
            val sctp = coroutineContext[SctpElement.Key]
            val quic = coroutineContext[QuicElement.Key]
            assertNotNull(sctp, "SctpElement must be present")
            assertNotNull(quic, "QuicElement must be present")
            // SCTP-first fallback pattern
            val transport = sctp ?: quic
            assertNotNull(transport)
        }
    }

    @Test
    fun indicatorContextService() = runTest {
        val emptySeries = 0 j { _: Int -> 0.0 }
        val indicators = SampleStrategySignals.Indicators(
            rsi = emptySeries,
            tema = emptySeries,
            bbMiddle = emptySeries,
            bbUpper = emptySeries,
            bbLower = emptySeries,
        )
        withContext(IndicatorContextService(indicators)) {
            val service = coroutineContext[IndicatorContextService.Key]
            assertNotNull(service)
            assertEquals(0, service.indicators.rsi.size)
        }
    }
}
