package borg.trikeshed.ccek

import borg.trikeshed.ccek.transport.NgSctpService
import borg.trikeshed.ccek.transport.QuicChannelService
import borg.trikeshed.common.HomeDirService
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.signal.IndicatorContextService
import borg.trikeshed.signal.SampleStrategySignals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class KeyedServiceTest {

    @Test
    fun baseComposition() = runTest {
        val ctx = HomeDirService("~") + QuicChannelService()
        withContext(ctx) {
            assertNotNull(coroutineContext[HomeDirService.Key], "HomeDirService must be present")
            assertNotNull(coroutineContext[QuicChannelService.Key], "QuicChannelService must be present")
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
        val ctx = NgSctpService() + QuicChannelService()
        withContext(ctx) {
            val sctp = coroutineContext[NgSctpService.Key]
            val quic = coroutineContext[QuicChannelService.Key]
            assertNotNull(sctp, "NgSctpService must be present")
            assertNotNull(quic, "QuicChannelService must be present")
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
