package borg.trikeshed.hermes

import borg.trikeshed.terminal.VtKey
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HermesLineUnicodeTest {
    @Test fun supplementaryCharactersAreEditedAsOneCharacter() {
        HermesVmConsole(Path.of("unused"), Path.of("unused"), columns = 30, rows = 4).use { console ->
            console.manualText("A😀B")
            console.manualKey(VtKey.LEFT)
            console.manualKey(VtKey.LEFT)
            assertEquals("A😀B" to 1, console.lineState())
            console.manualKey(VtKey.DELETE)
            assertEquals("AB" to 1, console.lineState())
            console.manualKey(VtKey.END)
            console.manualText("😀")
            console.manualKey(VtKey.BACKSPACE)
            assertEquals("AB" to 2, console.lineState())
            console.manualText("😀")
            console.manualKey(VtKey.HOME)
            console.manualKey(VtKey.RIGHT)
            console.manualKey(VtKey.RIGHT)
            console.manualKey(VtKey.RIGHT)
            assertEquals("AB😀" to 4, console.lineState())
        }
    }

    @Test fun combiningMarksStayWithTheirBaseDuringEditing() {
        HermesVmConsole(Path.of("unused"), Path.of("unused"), columns = 30, rows = 4).use { console ->
            console.manualText("Ae\u0301B")
            console.manualKey(VtKey.HOME)
            console.manualKey(VtKey.RIGHT)
            console.manualKey(VtKey.RIGHT)
            assertEquals("Ae\u0301B" to 3, console.lineState())
            console.manualKey(VtKey.BACKSPACE)
            assertEquals("AB" to 1, console.lineState())
            console.manualText("e\u0301")
            console.manualKey(VtKey.LEFT)
            console.manualKey(VtKey.DELETE)
            assertEquals("AB" to 1, console.lineState())
        }
    }

    @Test fun cursorAndScrollingUseTerminalCells() {
        HermesVmConsole(Path.of("unused"), Path.of("unused"), columns = 20, rows = 4).use { console ->
            console.manualText("界A")
            assertEquals(11, console.panel.terminal.cursor.column)
            console.manualKey(VtKey.LEFT)
            assertEquals(10, console.panel.terminal.cursor.column)
        }
        HermesVmConsole(Path.of("unused"), Path.of("unused"), columns = 12, rows = 4).use { console ->
            console.manualText("abcdef")
            assertEquals(0, console.panel.terminal.cursor.row)
            assertEquals(11, console.panel.terminal.cursor.column)
            assertTrue(console.panel.terminal.plainText().contains("hermes> def"))
            console.resize(16, 4)
            assertEquals(14, console.panel.terminal.cursor.column)
            assertTrue(console.panel.terminal.plainText().contains("hermes> abcdef"))
        }
    }
}
