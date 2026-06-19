package borg.trikeshed.reactor

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.toList
import borg.trikeshed.userspace.FanoutEvent
import borg.trikeshed.userspace.FanoutEventSubscriber
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class TlsEndpointTest {
    @Test
    fun ccekTlsElementChannelizesFramesThroughBackend() = runTest {
        val subscriber = RecordingTlsSubscriber()
        val fanoutSubscriber = RecordingFanoutSubscriber()
        val backend = FakeTlsCodecBackend()
        val tls = openTlsElement(
            backend = backend,
            subscribers = listOf(subscriber, fanoutSubscriber),
        )
        val endpoint = tls.clientEndpoint("example.com", 443)

        val handshakeFrames = endpoint.handshake().toList()
        val upstreamFrames = endpoint.upstream(ByteSeries("hello")).toList()
        val downstreamFrames = endpoint.downstream(ByteSeries(byteArrayOf(0x16, 0x03, 0x03))).toList()

        assertEquals(1, handshakeFrames.size)
        assertEquals(TlsFlowStage.HANDSHAKE, handshakeFrames.first().stage)
        assertTrue(endpoint.isHandshakeComplete)
        assertSame(TlsProtocol.TLS13, endpoint.session?.protocol)
        assertSame(TlsCipherSuite.TLS_AES_128_GCM_SHA256, endpoint.session?.cipherSuite)

        assertEquals(1, upstreamFrames.size)
        assertEquals(TlsFlowStage.UPSTREAM_CIPHERTEXT, upstreamFrames.first().stage)
        assertEquals("hello", upstreamFrames.first().payload.asString())

        assertEquals(1, downstreamFrames.size)
        assertEquals(TlsFlowStage.DOWNSTREAM_PLAINTEXT, downstreamFrames.first().stage)
        assertEquals("decoded", downstreamFrames.first().payload.asString())

        assertEquals(3, subscriber.batches.size)
        assertEquals(TlsFlowStage.HANDSHAKE, subscriber.batches[0].first().stage)
        assertEquals(TlsFlowStage.UPSTREAM_CIPHERTEXT, subscriber.batches[1].first().stage)
        assertEquals(TlsFlowStage.DOWNSTREAM_PLAINTEXT, subscriber.batches[2].first().stage)

        assertEquals(3, fanoutSubscriber.events.size)
        assertEquals(TlsFlowStage.HANDSHAKE, (fanoutSubscriber.events[0] as TlsChannelFrame).stage)
        assertEquals(TlsFlowStage.UPSTREAM_CIPHERTEXT, (fanoutSubscriber.events[1] as TlsChannelFrame).stage)
        assertEquals(TlsFlowStage.DOWNSTREAM_PLAINTEXT, (fanoutSubscriber.events[2] as TlsChannelFrame).stage)
    }
}

private class RecordingTlsSubscriber : AsyncContextElement(), TlsChannelSubscriber {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<RecordingTlsSubscriber>
    override val key: kotlin.coroutines.CoroutineContext.Key<*> get() = Key

    val batches = mutableListOf<List<TlsChannelFrame>>()

    override suspend fun onTlsFrames(frames: Series<TlsChannelFrame>) {
        batches += frames.toList()
    }
}

private class RecordingFanoutSubscriber : AsyncContextElement(), FanoutEventSubscriber {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<RecordingFanoutSubscriber>
    override val key: kotlin.coroutines.CoroutineContext.Key<*> get() = Key

    val events = mutableListOf<FanoutEvent>()

    override suspend fun onFanoutEvent(event: FanoutEvent) {
        events += event
    }
}

private class FakeTlsCodecBackend : TlsCodecBackend {
    override suspend fun handshake(
        config: TlsConfig,
        state: TlsFlowState,
    ): TlsCodecResult {
        val session = TlsSession(
            protocol = TlsProtocol.TLS13,
            cipherSuite = TlsCipherSuite.TLS_AES_128_GCM_SHA256,
            verified = true,
        )
        val next = state.copy(
            lifecycle = TlsConnectionState.OPEN,
            session = session,
        )
        return TlsCodecResult(
            next,
            tlsFrames(
                TlsChannelFrame(
                    route = state.route,
                    stage = TlsFlowStage.HANDSHAKE,
                    session = session,
                ),
            ),
        )
    }

    override suspend fun upstream(
        config: TlsConfig,
        state: TlsFlowState,
        payload: TlsPayload,
    ): TlsCodecResult =
        TlsCodecResult(
            state.copy(
                pendingUpstreamPlaintext = payload.clone(),
                pendingUpstreamCiphertext = ByteSeries("cipher:${
                    payload.asString()
                }"),
            ),
            tlsFrames(
                TlsChannelFrame(
                    route = state.route,
                    stage = TlsFlowStage.UPSTREAM_CIPHERTEXT,
                    payload = payload.clone(),
                    session = state.session,
                ),
            ),
        )

    override suspend fun downstream(
        config: TlsConfig,
        state: TlsFlowState,
        payload: TlsPayload,
    ): TlsCodecResult =
        TlsCodecResult(
            state.copy(
                pendingDownstreamCiphertext = payload.clone(),
                pendingDownstreamPlaintext = ByteSeries("decoded"),
            ),
            tlsFrames(
                TlsChannelFrame(
                    route = state.route,
                    stage = TlsFlowStage.DOWNSTREAM_PLAINTEXT,
                    payload = ByteSeries("decoded"),
                    session = state.session,
                ),
            ),
        )

    override suspend fun close(
        config: TlsConfig,
        state: TlsFlowState,
    ): TlsCodecResult =
        TlsCodecResult(
            state.copy(lifecycle = TlsConnectionState.CLOSED),
            tlsFrames(
                TlsChannelFrame(
                    route = state.route,
                    stage = TlsFlowStage.CLOSE_NOTIFY,
                    session = state.session,
                ),
            ),
        )
}
