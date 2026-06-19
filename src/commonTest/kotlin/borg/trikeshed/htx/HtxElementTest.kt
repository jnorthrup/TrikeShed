package borg.trikeshed.htx

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.lib.toList
import borg.trikeshed.userspace.FanoutEvent
import borg.trikeshed.userspace.FanoutEventSubscriber
import borg.trikeshed.userspace.nio.spi.NioSupervisor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class HtxElementTest {
    @Test
    fun ccekHtxElementChannelizesFramesThroughRouteService() = runTest {
        val frameSubscriber = RecordingHtxFrameSubscriber()
        val fanoutSubscriber = RecordingHtxFanoutSubscriber()
        val element = openHtxElement(
            routeService = FakeHtxRouteService,
            subscribers = listOf(frameSubscriber, fanoutSubscriber),
        )

        val response = element.request("get", "health")

        assertEquals(201, response.status)
        assertEquals("created", response.body.asString())
        assertEquals(1, frameSubscriber.batches.size)
        assertEquals(2, frameSubscriber.batches.first().size)
        assertEquals(HtxFlowStage.REQUEST, frameSubscriber.batches.first()[0].stage)
        assertEquals(HtxFlowStage.RESPONSE, frameSubscriber.batches.first()[1].stage)
        assertEquals(2, fanoutSubscriber.events.size)
        assertEquals(HtxFlowStage.REQUEST, (fanoutSubscriber.events[0] as HtxFrame).stage)
        assertEquals(HtxFlowStage.RESPONSE, (fanoutSubscriber.events[1] as HtxFrame).stage)
    }

    @Test
    fun ccekHtxElementResolvesRouteServiceFromSupervisor() = runTest {
        val supervisor = NioSupervisor()
        supervisor.register(FakeHtxRouteService)
        supervisor.open()

        val element = openHtxElement(nioSupervisor = supervisor)
        val response = element.request(parseHtxRequest("http://127.0.0.1/health"))

        assertEquals(201, response.status)
        assertEquals("created", response.body.asString())

        element.close()
    }
}

private class RecordingHtxFrameSubscriber : AsyncContextElement(), HtxFrameSubscriber {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<RecordingHtxFrameSubscriber>
    override val key: kotlin.coroutines.CoroutineContext.Key<*> get() = Key

    val batches = mutableListOf<List<HtxFrame>>()

    override suspend fun onHtxFrames(frames: HtxFrames) {
        batches += frames.toList()
    }
}

private class RecordingHtxFanoutSubscriber : AsyncContextElement(), FanoutEventSubscriber {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<RecordingHtxFanoutSubscriber>
    override val key: kotlin.coroutines.CoroutineContext.Key<*> get() = Key

    val events = mutableListOf<FanoutEvent>()

    override suspend fun onFanoutEvent(event: FanoutEvent) {
        events += event
    }
}

private object FakeHtxRouteService : HtxRouteService {
    override suspend fun exchange(
        state: HtxExchangeState,
        request: HtxRequest,
    ): HtxExchangeResult {
        val response = HtxResponse(201, ByteSeries("created"))
        val next = state.copy(
            lifecycle = HtxExchangeLifecycle.RESPONDED,
            request = request,
            response = response,
        )
        return HtxExchangeResult(
            next,
            htxFrames(
                HtxFrame(
                    exchangeOrdinal = state.exchangeOrdinal,
                    stage = HtxFlowStage.REQUEST,
                    request = request,
                ),
                HtxFrame(
                    exchangeOrdinal = state.exchangeOrdinal,
                    stage = HtxFlowStage.RESPONSE,
                    request = request,
                    response = response,
                ),
            ),
        )
    }
}
