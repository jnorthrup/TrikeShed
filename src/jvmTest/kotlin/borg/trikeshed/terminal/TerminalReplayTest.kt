package borg.trikeshed.terminal

import borg.trikeshed.forge.server.*
import borg.trikeshed.hermes.HermesVmConsole
import borg.trikeshed.parse.reifyMap
import borg.trikeshed.pointcut.VmFacet
import borg.trikeshed.vm.*
import java.nio.file.Path
import kotlinx.coroutines.*
import kotlin.test.*

class TerminalReplayTest {
    @Test
    fun hermesReplayKeepsEmissionGeometryInsteadOfCurrentGeometry() = runBlocking {
        val console = HermesVmConsole(Path.of("unused"), Path.of("unused"))
        val wire = HermesConsoleWire(console, this)
        try {
            console.resize(40, 8)
            val first = console.terminalMetaMap()
            console.resize(20, 4)
            val second = console.terminalMetaMap()
            val snapshot = wire.route("GET", "/api/hermes/terminal", "", null)!!.body
            val events = capture(2) { receive -> wire.route("GET", HermesConsoleWire.EVENTS_PATH, "", receive) }
            assertEquals(listOf(first, second).map { reifyMap(borg.trikeshed.parse.jsonOf(it)) },
                events.map { it["terminal"] })
            assertEquals((events.last()["signal"] as Map<*, *>)["sequence"],
                (console.snapshotMap()["sequence"] as Number).toDouble())
            assertEquals(events, capture(2) { receive -> wire.route("GET", HermesConsoleWire.EVENTS_PATH, "", receive) })
            assertEquals(snapshot, wire.route("GET", "/api/hermes/terminal", "", null)!!.body)
            assertEquals(HermesVmConsole.State.CLOSED, console.state, "Observing and reconnecting must not start a guest")
        } finally { wire.drain() }
    }

    @Test
    fun vmReplayKeepsCursorOnlyEventsAndTheirOriginalGeometry() = runBlocking {
        val host = HypervisorVmHost()
        val wire = VmWire(host, this)
        try {
            val terminal = host.terminals.open("replay", VmFacet.GRAAL_JS, "test")
            terminal.resize(40, 8)
            val beforeCursor = terminal.terminalMetaMap()
            terminal.systemOutput("\u001b[2;3H")
            val afterCursor = terminal.terminalMetaMap()
            assertEquals(beforeCursor["revision"], afterCursor["revision"], "Cursor motion need not change cell revision")
            assertNotEquals(beforeCursor["cursor"], afterCursor["cursor"])
            terminal.resize(20, 4)
            val count = host.terminals.events.replayCache.size
            val snapshot = wire.route("GET", "/api/vm/replay/terminal", "", null)!!.body
            val events = capture(count) { receive -> wire.route("GET", "/api/vm/terminal/events", "", receive) }
            assertEquals(reifyMap(borg.trikeshed.parse.jsonOf(beforeCursor)), events[2]["terminal"])
            assertEquals(reifyMap(borg.trikeshed.parse.jsonOf(afterCursor)), events[3]["terminal"])
            assertEquals(emptyList<Any?>(), events[3]["patches"], "Cursor-only output must still be emitted")
            assertTrue((events[3]["signal"] as Map<*, *>)["sequence"] as Double >
                (events[2]["signal"] as Map<*, *>)["sequence"] as Double)
            assertEquals((events.last()["signal"] as Map<*, *>)["sequence"],
                (terminal.snapshotMap()["sequence"] as Number).toDouble())
            assertEquals(events, capture(count) { receive -> wire.route("GET", "/api/vm/terminal/events", "", receive) })
            assertEquals(snapshot, wire.route("GET", "/api/vm/replay/terminal", "", null)!!.body)
            assertEquals(emptyList<String>(), host.ids(), "Observing and reconnecting must not spawn or evaluate a guest")
        } finally { host.close() }
    }

    private suspend fun capture(count: Int, stream: suspend (suspend (ByteArray) -> Unit) -> Any?): List<Map<String, Any?>> {
        val events = mutableListOf<Map<String, Any?>>()
        try {
            withTimeout(5_000) {
                stream { bytes ->
                    val text = bytes.decodeToString()
                    if (text.startsWith("data: ")) {
                        events += reifyMap(text.removePrefix("data: ").trim())
                        if (events.size == count) throw CancellationException("replay captured")
                    }
                }
            }
        } catch (_: CancellationException) { }
        assertEquals(count, events.size, "Every retained event must serialize after subsequent terminal mutations")
        return events
    }
}
