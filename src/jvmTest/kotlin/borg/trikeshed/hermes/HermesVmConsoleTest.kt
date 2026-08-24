package borg.trikeshed.hermes

import borg.trikeshed.forge.server.HermesConsoleWire
import borg.trikeshed.lcnc.media.LcncSignalLane
import borg.trikeshed.lib.view
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HermesVmConsoleTest {
    @Test
    fun vt220ConsoleBootsSleeveCapturesGuestOutputAndBondsCausalSignals() {
        HermesVmConsole(Path.of("unused"), Path.of("unused"), columns = 64, rows = 12).use { console ->
            console.inventoryLoader = { port ->
                port.inventorySources(
                    mapOf(
                        "hermes_cli" to ("hermes_cli/__init__.py" to "NAME = 'hermes'"),
                        "hermes_cli.main" to ("hermes_cli/main.py" to "READY = True"),
                    ),
                )
            }

            assertEquals(HermesVmConsole.State.READY, console.open(timestampMs = 1))
            val manual = console.submit(":eval print('\\x1b[31mguest-red\\x1b[0m') or 42", timestampMs = 2)
            val snapshot = console.snapshotMap()

            assertEquals("ready", snapshot["state"])
            val text = console.panel.terminal.plainText()
            assertTrue(text.contains("guest-red"), text)
            assertTrue(text.contains("hermes>"), text)
            val signals = console.panel.signals().view.toList()
            assertTrue(signals.any { it.id == manual.id && it.lane == LcncSignalLane.MANUAL })
            assertTrue(signals.any { it.lane == LcncSignalLane.CAUSAL && it.causeSignalId == manual.id })
            val redCell = console.panel.snapshot().lines.view
                .flatMap { it.view }
                .first { it.text == "g" && it.style.foreground.index == 1 }
            assertEquals("g", redCell.text)
        }
    }

    @Test
    fun supervisedVfsProvidesDevNullWithoutHostFileAccess() {
        HermesVmConsole(Path.of("unused"), Path.of("unused"), columns = 40, rows = 8).use { console ->
            console.inventoryLoader = { port ->
                port.inventorySources(
                    mapOf(
                        "hermes_cli" to ("hermes_cli/__init__.py" to ""),
                        "hermes_cli.main" to ("hermes_cli/main.py" to ""),
                    ),
                )
            }
            assertEquals(HermesVmConsole.State.READY, console.open())
            console.submit(":eval open('/dev/null','w').write('discarded')")
            assertTrue(console.panel.terminal.plainText().contains("9"))
        }
    }

    @Test
    fun wireServesVt220PageSnapshotResizeAndAsyncCommand() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            HermesVmConsole(Path.of("unused"), Path.of("unused"), columns = 40, rows = 8).use { console ->
                console.inventoryLoader = { port ->
                    port.inventorySources(mapOf(
                        "hermes_cli" to ("hermes_cli/__init__.py" to ""),
                        "hermes_cli.main" to ("hermes_cli/main.py" to ""),
                    ))
                }
                console.open()
                val wire = HermesConsoleWire(console, scope)
                val page = wire.route("GET", "/hermes", "", null)
                assertEquals(200, page?.status)
                assertTrue(page?.bytes?.decodeToString()?.contains("HERMES · VT220") == true)
                val snapshot = wire.route("GET", "/api/hermes/terminal", "", null)
                assertTrue(snapshot?.body?.contains("\"kind\":\"vt220\"") == true)
                val resized = wire.route(
                    "POST", "/api/hermes/terminal/resize",
                    "POST / HTTP/1.1\r\n\r\n{\"columns\":52,\"rows\":9}", null,
                )
                assertEquals(200, resized?.status)
                val submitted = wire.route(
                    "POST", "/api/hermes/terminal/input",
                    "POST / HTTP/1.1\r\n\r\n{\"command\":\":status\"}", null,
                )
                assertEquals(202, submitted?.status)
                withTimeout(5_000) {
                    while (console.panel.signals().view.count { it.kind == "completed" } == 0) delay(10)
                }
                assertTrue(console.panel.terminal.plainText().contains("state=ready"))
            }
        } finally {
            scope.cancel()
        }
    }
}
