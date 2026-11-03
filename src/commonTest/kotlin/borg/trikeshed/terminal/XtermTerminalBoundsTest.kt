package borg.trikeshed.terminal

import borg.trikeshed.lib.*
import kotlin.test.*

class XtermTerminalBoundsTest {
    @Test fun largeCursorMovementsStayInsideTheScreen() {
        val terminal = XtermTerminal(4, 3)
        terminal.feed("a\r\nb")
        terminal.feed("\u001b[2147483647B")
        assertEquals(2, terminal.cursor.row)
        terminal.feed("\u001b[2147483647C")
        assertEquals(3, terminal.cursor.column)
        terminal.feed("\u001b[2147483647X")
        assertEquals(2, terminal.cursor.row)
        assertEquals(3, terminal.cursor.column)
    }

    @Test fun oversizedParametersSaturateWithinOriginRegion() {
        val terminal = XtermTerminal(4, 4)
        terminal.feed("\u001b[2;3r\u001b[?6h\u001b[999999999999999999999d")
        assertEquals(2, terminal.cursor.row)
        terminal.feed("\u001b[999999999999999999999C")
        assertEquals(3, terminal.cursor.column)
        terminal.feed("Z\u001b[999999999999999999999X")
        assertEquals(" ", terminal.snapshot().lines[2][3].text)
    }

    @Test fun hugeScrollRetainsOnlyTheBoundedBlankHistory() {
        val terminal = XtermTerminal(3, 2, scrollbackLimit = 3)
        terminal.feed("A\r\nB")
        val before = terminal.revision
        terminal.feed("\u001b[2147483647S")
        val snapshot = terminal.snapshot()
        assertEquals(before + 2147483647L, snapshot.revision)
        assertEquals(3, snapshot.scrollback.size)
        for (r in 0 until snapshot.scrollback.size) for (c in 0 until 3) {
            assertEquals(" ", snapshot.scrollback[r][c].text)
        }
        terminal.feed("\u001b[2147483647T")
        assertEquals(snapshot.revision + 2147483647L, terminal.revision)
        assertEquals(" ", terminal.snapshot().lines[0][0].text)
    }
}
