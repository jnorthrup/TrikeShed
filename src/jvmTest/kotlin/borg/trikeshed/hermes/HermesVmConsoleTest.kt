package borg.trikeshed.hermes

import borg.trikeshed.lcnc.media.LcncSignalLane
import borg.trikeshed.lib.view
import java.nio.file.Path
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
}
