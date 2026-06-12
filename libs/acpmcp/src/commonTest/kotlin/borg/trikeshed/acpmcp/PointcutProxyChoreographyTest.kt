package borg.trikeshed.acpmcp

import borg.trikeshed.lib.toSeries
import borg.trikeshed.lib.view
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proxy choreography parity test.
 *
 * Runs on JVM, JS (Node), and native (macosArm64).
 * Verifies that the non-JVM proxy transport produces the same
 * reactor choreography as the direct JVM classfile path,
 * acknowledging that performance (latency) will differ.
 */
class PointcutProxyChoreographyTest {
    @Test
    fun `proxy transport routes sample events into reactor with same choreography shape`() = runTest {
        val reactor = PointcutReactorElement()
        reactor.open()

        val transport = InMemoryPointcutProxyTransport(AcpmcpProtocol.ACP, "jvm-reactor")
        val facade = pointcutProxyFacade()

        // Simulate the events that the JVM-side classfile harness would produce.
        // On non-JVM targets, these arrive over the network proxy.
        val sampleEvents = listOf(
            PointcutRouteEvent(PointcutRoutePhase.BEFORE, "GETFIELD", 1, 32, 0, "Fixture.java", 15, "jvm"),
            PointcutRouteEvent(PointcutRoutePhase.AFTER, "GETFIELD", 1, 32, 1, "Fixture.java", 15, "jvm"),
            PointcutRouteEvent(PointcutRoutePhase.BEFORE, "IDIV", 2, 48, 2, "Fixture.java", 20, "jvm"),
            PointcutRouteEvent(PointcutRoutePhase.AFTER, "IDIV", 2, 48, 3, "Fixture.java", 20, "jvm"),
        ).toSeries()

        val report = facade.routeViaProxy(reactor, transport, sampleEvents)
        val events = reactor.events()

        // Choreography parity: same event count, same phases, same opcodes
        assertEquals(sampleEvents.a, events.a, "proxy should route all sample events into reactor")
        assertEquals(sampleEvents.a, report.routed)

        val opcodes = events.view.map { it.jvmOpcode }.toSet()
        val phases = events.view.map { it.phase }.toSet()
        assertTrue("GETFIELD" in opcodes, "proxy should deliver GETFIELD opcode")
        assertTrue("IDIV" in opcodes, "proxy should deliver IDIV opcode")
        assertEquals(setOf(PointcutRoutePhase.BEFORE, PointcutRoutePhase.AFTER), phases,
            "proxy should preserve dual-phase choreography")

        // Every site has BEFORE+AFTER pair — same as JVM direct path
        assertTrue(events.view.groupBy { it.jvmOpcode to it.addr }.values.all { site ->
            site.map { it.phase }.toSet() == setOf(PointcutRoutePhase.BEFORE, PointcutRoutePhase.AFTER)
        }, "every routed site should have BEFORE+AFTER pair parity with JVM direct facade")

        // Transport was used: frames sent
        assertTrue(transport.sentFrames.a > 0, "proxy transport should have sent frames over network")
    }

    @Test
    fun `proxy endpoint implements AcpmcpEndpoint and participates in shell choreography`() = runTest {
        val acp = ProxyBackedEndpoint(AcpmcpProtocol.ACP, "jvm-reactor")
        val mcp = ProxyBackedEndpoint(AcpmcpProtocol.MCP, "mcp-tools")
        val reactor = AcpmcpReactorElement()
        reactor.open()

        val calls = listOf(
            AcpmcpCall.acp("session-1", "pointcut.scan", "{\"opcodes\":\"value\"}"),
            AcpmcpCall.mcp("classfile-tools", "tools/list", "{}"),
        ).toSeries()

        val choreography = AcpmcpShell(reactor, acp, mcp).dispatch(calls)
        val phases = choreography.view.map { it.phase }.toList()

        // Same choreography shape as the direct JVM test — 6 steps for 2 calls
        assertEquals(6, choreography.a)
        assertEquals(
            listOf(
                ReactorChoreographyPhase.ACCEPT,
                ReactorChoreographyPhase.DISPATCH,
                ReactorChoreographyPhase.COMPLETE,
                ReactorChoreographyPhase.ACCEPT,
                ReactorChoreographyPhase.DISPATCH,
                ReactorChoreographyPhase.COMPLETE,
            ),
            phases,
            "proxy-backed endpoints must produce same choreography phases as direct endpoints",
        )
    }

    @Test
    fun `requestScanViaProxy routes scan request and receives events through transport`() = runTest {
        val reactor = PointcutReactorElement()
        reactor.open()

        val transport = ScanResponseProxyTransport()
        val facade = pointcutProxyFacade()
        val request = PointcutScanRequest(scanId = 1, opcodeFilter = "value", language = "jvm")

        val report = facade.requestScanViaProxy(reactor, transport, request)

        // The scan response should have been decoded and events fed into reactor
        assertTrue(report.routed > 0, "scan should have routed events through proxy")
        val events = reactor.events()
        assertEquals(report.routed, events.a)

        // Verify the sample response events arrived
        val opcodes = events.view.map { it.jvmOpcode }.toSet()
        assertTrue("GETFIELD" in opcodes, "scan response should contain GETFIELD events")
        assertTrue("IDIV" in opcodes, "scan response should contain IDIV events")

        // Verify dual-phase choreography parity
        val phases = events.view.map { it.phase }.toSet()
        assertEquals(setOf(PointcutRoutePhase.BEFORE, PointcutRoutePhase.AFTER), phases)

        // Every site should have BEFORE+AFTER pair
        assertTrue(events.view.groupBy { it.jvmOpcode to it.addr }.values.all { site ->
            site.map { it.phase }.toSet() == setOf(PointcutRoutePhase.BEFORE, PointcutRoutePhase.AFTER)
        }, "every scan response site should have BEFORE+AFTER pair parity")
    }

    /** In-memory transport for testing — simulates network without actual sockets. */
    private class InMemoryPointcutProxyTransport(
        override val protocol: AcpmcpProtocol,
        private val peer: String,
    ) : PointcutProxyTransport {
        private val _sent = mutableListOf<AcpmcpFrame>()
        val sentFrames get() = _sent.toList().toSeries()

        override suspend fun send(frame: AcpmcpFrame): AcpmcpFrame {
            _sent += frame
            return frame.copy(payload = "ack:$peer:${frame.method}")
        }

        override suspend fun close() {}
    }

    /** Transport that responds to scan requests with a sample response payload. */
    private class ScanResponseProxyTransport : PointcutProxyTransport {
        override val protocol: AcpmcpProtocol = AcpmcpProtocol.ACP
        private val _sent = mutableListOf<AcpmcpFrame>()
        val sentFrames get() = _sent.toList().toSeries()

        override suspend fun send(frame: AcpmcpFrame): AcpmcpFrame {
            _sent += frame
            return if (frame.method == "pointcut.scan") {
                frame.copy(payload = sampleScanResponse)
            } else {
                frame.copy(payload = "ack:${frame.method}")
            }
        }

        override suspend fun close() {}

        companion object {
            val sampleScanResponse = "{\"scanId\":1,\"events\":[" +
                "{\"phase\":\"BEFORE\",\"jvmOpcode\":\"GETFIELD\",\"methodIdx\":1,\"addr\":32,\"templateIdx\":0,\"sourceFile\":\"Fixture.java\",\"sourceLine\":15,\"sourceLanguage\":\"jvm\"}," +
                "{\"phase\":\"AFTER\",\"jvmOpcode\":\"GETFIELD\",\"methodIdx\":1,\"addr\":32,\"templateIdx\":1,\"sourceFile\":\"Fixture.java\",\"sourceLine\":15,\"sourceLanguage\":\"jvm\"}," +
                "{\"phase\":\"BEFORE\",\"jvmOpcode\":\"IDIV\",\"methodIdx\":2,\"addr\":48,\"templateIdx\":2,\"sourceFile\":\"Fixture.java\",\"sourceLine\":20,\"sourceLanguage\":\"jvm\"}," +
                "{\"phase\":\"AFTER\",\"jvmOpcode\":\"IDIV\",\"methodIdx\":2,\"addr\":48,\"templateIdx\":3,\"sourceFile\":\"Fixture.java\",\"sourceLine\":20,\"sourceLanguage\":\"jvm\"}" +
                "]}"
        }
    }

    /** Endpoint backed by an in-memory proxy transport. */
    private class ProxyBackedEndpoint(
        override val protocol: AcpmcpProtocol,
        private val peer: String,
    ) : AcpmcpEndpoint {
        override suspend fun invoke(frame: AcpmcpFrame): AcpmcpFrame =
            frame.copy(payload = "ack:$peer:${frame.method}")
    }
}
