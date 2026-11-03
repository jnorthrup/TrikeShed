package borg.trikeshed.hermes

import borg.trikeshed.lib.*
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HermesLinePasteTest {
    @Test fun mixedPasteMatchesSequentialEditingAndCommands() {
        val chunks = _l["a", "😀", "界", "\u0002", "Z", "\u007f", "Q", "\u0001", "!", "\r\n", "next", "\u0002", "?"]
        val input = chunks.joinToString("")
        HermesVmConsole(Path.of("unused"), Path.of("unused"), columns = 30, rows = 4).use { pasted ->
            HermesVmConsole(Path.of("unused"), Path.of("unused"), columns = 30, rows = 4).use { sequential ->
                val batchedCommands = mutableListOf<String>()
                val sequentialCommands = mutableListOf<String>()
                pasted.commandSink = { batchedCommands.add(it.signal.payload) }
                sequential.commandSink = { sequentialCommands.add(it.signal.payload) }
                val manual = pasted.manualText(input, timestampMs = 1, paste = true)
                for (chunk in chunks) sequential.manualText(chunk, timestampMs = 1)
                assertEquals(sequentialCommands, batchedCommands)
                assertEquals(_l["!a😀Q界"], batchedCommands)
                assertEquals(sequential.lineState(), pasted.lineState())
                assertEquals(sequential.panel.terminal.plainText(), pasted.panel.terminal.plainText())
                assertEquals(sequential.panel.terminal.cursor, pasted.panel.terminal.cursor)
                assertEquals("paste", manual.signal.kind)
                assertEquals(input, manual.signal.payload)
                assertEquals(1, pasted.panel.signals().view.count { it.kind == "paste" })
            }
        }
    }

    @Test fun printablePasteEmitsOneRedrawAndKeepsCodePointCapacityRules() {
        HermesVmConsole(Path.of("unused"), Path.of("unused"), columns = 30, rows = 4).use { console ->
            val input = "x".repeat(4_096)
            val manual = console.manualText(input, timestampMs = 1, paste = true)
            assertEquals(input, console.lineState().first)
            assertEquals(2L, console.panel.signalSequence, "one manual signal and one terminal redraw")
            assertEquals(input, manual.signal.payload)
        }
        HermesVmConsole(Path.of("unused"), Path.of("unused"), columns = 30, rows = 4).use { console ->
            console.manualText("x".repeat(4_095), paste = true)
            console.manualText("😀y", paste = true)
            assertEquals("x".repeat(4_095) + "y", console.lineState().first)
        }
        HermesVmConsole(Path.of("unused"), Path.of("unused"), columns = 30, rows = 4).use { console ->
            console.manualText("x".repeat(4_094), paste = true)
            console.manualText("😀y", paste = true)
            assertTrue(console.lineState().first.endsWith("😀"))
            assertEquals(4_096, console.lineState().first.length)
        }
    }
}
