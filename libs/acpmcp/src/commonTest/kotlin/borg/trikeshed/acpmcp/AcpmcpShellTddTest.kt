package borg.trikeshed.acpmcp

import borg.trikeshed.lib.toSeries
import borg.trikeshed.lib.view
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AcpmcpShellTddTest {
    @Test
    fun `shell interleaves ACP and MCP calls through reactor choreography`() = runTest {
        val acp = RecordingEndpoint(AcpmcpProtocol.ACP, "acp-agent")
        val mcp = RecordingEndpoint(AcpmcpProtocol.MCP, "mcp-tools")
        val reactor = AcpmcpReactorElement()
        reactor.open()

        val calls = listOf(
            AcpmcpCall.acp("session-1", "agent.run", "{\"prompt\":\"hi\"}"),
            AcpmcpCall.mcp("filesystem", "tools/list", "{}"),
        ).toSeries()

        val choreography = AcpmcpShell(reactor, acp, mcp).dispatch(calls)
        val frames = reactor.frames()
        val phases = reactor.choreography().view.map { it.phase }.toList()

        assertEquals(6, choreography.a, "each call should record ACCEPT, DISPATCH, COMPLETE")
        assertEquals(2, frames.a, "reactor should retain one frame per ACP/MCP call")
        assertEquals(listOf(AcpmcpProtocol.ACP, AcpmcpProtocol.MCP), frames.view.map { it.protocol }.toList())
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
        )
        assertEquals(listOf("agent.run"), acp.received.view.map { it.method }.toList())
        assertEquals(listOf("tools/list"), mcp.received.view.map { it.method }.toList())
        assertTrue(choreography.view.all { it.frame.seq >= 0 })
    }

    @Test
    fun `reactor fans choreography to subscribers without leaving algebra boundary`() = runTest {
        val subscriber = AcpmcpReactorElement()
        val reactor = AcpmcpReactorElement(subscribers = listOf(subscriber))
        reactor.open()
        subscriber.open()

        val frame = AcpmcpFrame(
            protocol = AcpmcpProtocol.MCP,
            peer = "filesystem",
            method = "resources/read",
            payload = "{\"uri\":\"file:///tmp/a\"}",
            seq = 7,
        )

        reactor.accept(frame)
        reactor.dispatch(frame)
        reactor.complete(frame, "{\"ok\":true}")

        assertEquals(3, reactor.choreography().a)
        assertEquals(3, subscriber.choreography().a, "subscriber should receive the same three choreography steps")
        assertEquals(
            reactor.choreography().view.map { it.phase to it.frame.seq }.toList(),
            subscriber.choreography().view.map { it.phase to it.frame.seq }.toList(),
        )
        assertEquals(listOf(frame), reactor.frames().view.toList())
    }

    @Test
    fun `dispatchPointcutScan frames scan as ACP call through reactor choreography`() = runTest {
        val acp = RecordingEndpoint(AcpmcpProtocol.ACP, "classfile-reactor")
        val mcp = RecordingEndpoint(AcpmcpProtocol.MCP, "mcp-tools")
        val reactor = AcpmcpReactorElement()
        reactor.open()
        val pointcutReactor = PointcutReactorElement()
        pointcutReactor.open()

        val shell = AcpmcpShell(reactor, acp, mcp)
        val request = PointcutScanRequest(scanId = 42, opcodeFilter = "value", language = "jvm")
        val (choreography, report) = shell.dispatchPointcutScan(pointcutReactor, request)

        // The scan should produce ACCEPT → DISPATCH → COMPLETE choreography
        assertEquals(3, choreography.a, "pointcut scan should produce 3 choreography steps")
        assertEquals(
            listOf(
                ReactorChoreographyPhase.ACCEPT,
                ReactorChoreographyPhase.DISPATCH,
                ReactorChoreographyPhase.COMPLETE,
            ),
            choreography.view.map { it.phase }.toList(),
        )
        // The scan frame should be routed as ACP
        assertEquals(AcpmcpProtocol.ACP, choreography.view.first().frame.protocol)
        assertEquals("pointcut.scan", choreography.view.first().frame.method)
        assertEquals(42, choreography.view.first().frame.seq)
        // Pointcut reactor should have events
        assertTrue(report.routed > 0, "pointcut scan should route events into reactor")
    }

    @Test
    fun `scan request codec round-trips correctly`() {
        val request = PointcutScanRequest(scanId = 7, opcodeFilter = "value", language = "jvm")
        val encoded = encodePointcutScanRequest(request)
        assertTrue(encoded.contains("\"scanId\":7"))
        assertTrue(encoded.contains("\"opcodeFilter\":\"value\""))
        assertTrue(encoded.contains("\"language\":\"jvm\""))
    }

    @Test
    fun `scan response codec decodes sample events`() {
        val payload = "{\"scanId\":3,\"events\":[" +
            "{\"phase\":\"BEFORE\",\"jvmOpcode\":\"GETFIELD\",\"methodIdx\":1,\"addr\":32,\"templateIdx\":0,\"sourceFile\":\"Fixture.java\",\"sourceLine\":15,\"sourceLanguage\":\"jvm\"}," +
            "{\"phase\":\"AFTER\",\"jvmOpcode\":\"GETFIELD\",\"methodIdx\":1,\"addr\":32,\"templateIdx\":1,\"sourceFile\":\"Fixture.java\",\"sourceLine\":15,\"sourceLanguage\":\"jvm\"}" +
            "]}"
        val response = decodePointcutScanResponse(payload)
        assertEquals(3, response.scanId)
        assertEquals(2, response.events.a)
        assertEquals(PointcutRoutePhase.BEFORE, response.events.b(0).phase)
        assertEquals("GETFIELD", response.events.b(0).jvmOpcode)
        assertEquals(PointcutRoutePhase.AFTER, response.events.b(1).phase)
        assertEquals("Fixture.java", response.events.b(0).sourceFile)
        assertEquals(15, response.events.b(0).sourceLine)
    }

    private class RecordingEndpoint(
        override val protocol: AcpmcpProtocol,
        private val name: String,
    ) : AcpmcpEndpoint {
        private val captured = mutableListOf<AcpmcpFrame>()
        val received get() = captured.toSeries()

        override suspend fun invoke(frame: AcpmcpFrame): AcpmcpFrame {
            captured += frame
            return frame.copy(payload = "ack:$name:${frame.method}")
        }
    }
}
