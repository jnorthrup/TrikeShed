package borg.trikeshed.reactor

import borg.trikeshed.lib.j
import borg.trikeshed.lib.Join
import kotlin.test.*

class ReactorAlgebraTest {

    // 1. identityIsUnitForHttpGet — project(block, Identity) for an HTTP GET block returns a block equal-by-fields to the input.
    @Test
    fun identityIsUnitForHttpGet() {
        val session = SessionState.new(Protocol.Http.id)
        val block: WamBlock = session j TransformCode.HttpTransform(HttpMethod.Get)
        val result = project(block, TransformCode.Identity)
        assertEquals(block.a, result.a)
        assertEquals(block.b, result.b)
    }

    // 2. identityIsUnitForSocks5Connect — same as above but for Socks5Connect.
    @Test
    fun identityIsUnitForSocks5Connect() {
        val session = SessionState.new(Protocol.Socks5.id)
        val block: WamBlock = session j TransformCode.Socks5Transform(Socks5Command.Connect)
        val result = project(block, TransformCode.Identity)
        assertEquals(block.a, result.a)
        assertEquals(block.b, result.b)
    }

    // 3. unknownProtocolIdRejects — Protocol.fromId(99u.toUByte()) returns null.
    @Test
    fun unknownProtocolIdRejects() {
        assertNull(Protocol.fromId(99u.toUByte()))
    }

    // 4. roundTripProtocolId — every Protocol.entries maps to a unique id 1..7 and fromId(it.id) == it.
    @Test
    fun roundTripProtocolId() {
        val ids = mutableSetOf<UByte>()
        for (protocol in Protocol.entries) {
            assertTrue(protocol.id in 1u.toUByte()..7u.toUByte())
            assertTrue(ids.add(protocol.id))
            assertEquals(protocol, Protocol.fromId(protocol.id))
        }
        assertEquals(7, ids.size)
    }

    // 5. sessionStateNewIsIdle — SessionState.new(Protocol.Http.id) has connectionState == Idle, parsingPosition == 0, continuationPoint == null.
    @Test
    fun sessionStateNewIsIdle() {
        val state = SessionState.new(Protocol.Http.id)
        assertEquals(ConnectionState.Idle, state.connectionState)
        assertEquals(0, state.parsingPosition)
        assertNull(state.continuationPoint)
    }

    // 6. wamBlockJoinShape — WamBlock is a Join<SessionState, TransformCode>; unpacking via .a and .b returns the right pair.
    @Test
    fun wamBlockJoinShape() {
        val session = SessionState.new(Protocol.Http.id)
        val code = TransformCode.Identity
        val block: WamBlock = session j code

        val a: SessionState = block.a
        val b: TransformCode = block.b

        assertEquals(session, a)
        assertEquals(code, b)
    }

    // 7. channelMessageShape — ChannelMessage exposes protocol, verb, payload in that order via .a, .a.b, .a.b.b.
    @Test
    fun channelMessageShape() {
        val payload = byteArrayOf(1, 2, 3)
        val msg: ChannelMessage = Protocol.Http j ("GET" j payload)
        assertEquals(Protocol.Http, msg.a)
        assertEquals("GET", msg.b.a)
        assertEquals(payload, msg.b.b)
    }

    // 8. channelResponseErrIsLoud — a ChannelResponse.Err(ReactorError.EmptyPayload) is not equal to NoOp and not equal to Ok.
    @Test
    fun channelResponseErrIsLoud() {
        val err = ChannelResponse.Err(ReactorError.EmptyPayload)
        val noOp = ChannelResponse.NoOp
        val ok = ChannelResponse.Ok(byteArrayOf())

        assertNotEquals<ChannelResponse>(err, noOp)
        assertNotEquals<ChannelResponse>(err, ok)
    }

    // 9. defaultConfigPrioritiesAreDistinct — the default config has 7 entries, no priority repeats more than 3 times.
    @Test
    fun defaultConfigPrioritiesAreDistinct() {
        val config = ReactorConfig.default()
        val counts = mutableMapOf<Int, Int>()

        var totalEntries = 0
        for (i in 0 until config.priorities.a) {
            val prio = config.priorities.b(i).priority
            counts[prio] = counts.getOrElse(prio) { 0 } + 1
            totalEntries++
        }

        assertEquals(7, totalEntries)
        for (count in counts.values) {
            assertTrue(count <= 3)
        }
    }

    // 10. projectClosesSession — applying a transform that sets connectionState = Closed to an Active block yields a block whose a.connectionState == Closed.
    @Test
    fun projectClosesSession() {
        val activeSession = SessionState.new(Protocol.Http.id).copy(connectionState = ConnectionState.Active)
        val block: WamBlock = activeSession j TransformCode.HttpTransform(HttpMethod.Get)

        // As specified: we apply a non-Identity transform and it should return a Closed session block
        val result = project(block, TransformCode.HttpTransform(HttpMethod.Get)) // Applying a non-identity code

        assertEquals(ConnectionState.Closed, result.a.connectionState)
    }

    // 11. identityIsUnitForAllProtocols — for each Protocol.entries value, project(makeBlock(it), Identity) returns a block equal-by-fields to the input. (For-loop over Protocol.entries.)
    @Test
    fun identityIsUnitForAllProtocols() {
        for (protocol in Protocol.entries) {
            val session = SessionState.new(protocol.id)
            val block: WamBlock = session j TransformCode.Identity
            val result = project(block, TransformCode.Identity)
            assertEquals(block.a, result.a)
            assertEquals(block.b, result.b)
        }
    }
}
