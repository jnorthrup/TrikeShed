package borg.trikeshed.net.channelization

import borg.trikeshed.net.ProtocolId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChannelSessionTest {

    @Test
    fun sessionIdValueEquality() {
        val id1 = ChannelSessionId("abc")
        val id2 = ChannelSessionId("abc")
        val id3 = ChannelSessionId("def")

        assertEquals(id1, id2)
        assertEquals(id1.raw, "abc")
        assertTrue(id1 != id3)
    }

    @Test
    fun sessionStateTransitions() {
        val states = listOf(
            ChannelSessionState.Initialized,
            ChannelSessionState.Active,
            ChannelSessionState.Draining,
            ChannelSessionState.Terminated,
            ChannelSessionState.Failed(RuntimeException("fail")),
        )

        states.forEach { state ->
            when (state) {
                ChannelSessionState.Active -> assertTrue(canAcceptFrames(state))
                else -> assertFalse(canAcceptFrames(state))
            }
        }
    }

    private fun canAcceptFrames(state: ChannelSessionState): Boolean {
        val mockSession = object : ChannelSession {
            override val id: ChannelSessionId = ChannelSessionId("test")
            override val protocol: ProtocolId = ProtocolId.HTTP
            override val state: ChannelSessionState = state
            override fun transitionTo(newState: ChannelSessionState) {}
        }
        return mockSession.canAcceptFrames()
    }

    @Test
    fun channelSessionLifecycle() {
        val session = MockChannelSession(ChannelSessionId("session-1"), ProtocolId.QUIC)

        assertEquals(ChannelSessionState.Initialized, session.state)
        assertFalse(session.canAcceptFrames())

        session.transitionTo(ChannelSessionState.Active)
        assertEquals(ChannelSessionState.Active, session.state)
        assertTrue(session.canAcceptFrames())

        session.transitionTo(ChannelSessionState.Draining)
        assertEquals(ChannelSessionState.Draining, session.state)
        assertFalse(session.canAcceptFrames())

        session.transitionTo(ChannelSessionState.Terminated)
        assertEquals(ChannelSessionState.Terminated, session.state)
        assertFalse(session.canAcceptFrames())
    }

    @Test
    fun channelSessionFailureState() {
        val session = MockChannelSession(ChannelSessionId("session-error"), ProtocolId.HTTP)
        val error = IllegalStateException("boom")
        
        session.transitionTo(ChannelSessionState.Failed(error))
        
        val currentState = session.state
        assertTrue(currentState is ChannelSessionState.Failed)
        assertEquals(error, (currentState as ChannelSessionState.Failed).reason)
        assertFalse(session.canAcceptFrames())
    }

    private class MockChannelSession(
        override val id: ChannelSessionId,
        override val protocol: ProtocolId,
        initialState: ChannelSessionState = ChannelSessionState.Initialized
    ) : ChannelSession {
        private var _state: ChannelSessionState = initialState
        override val state: ChannelSessionState get() = _state

        override fun transitionTo(newState: ChannelSessionState) {
            _state = newState
        }
    }
}
