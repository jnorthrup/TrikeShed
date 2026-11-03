package borg.trikeshed.terminal

import borg.trikeshed.lib.*
import kotlin.test.*

class XtermTerminalBoundsTest {
    @Test fun alternateScreenResizeKeepsTheSavedScreenWithinTheNewGeometry() {
        val terminal = XtermTerminal(4, 2)
        terminal.feed("ABCD\r\nEFGH\u001b[?1049h")
        terminal.resize(2, 1)
        terminal.resize(6, 3)
        terminal.feed("\u001b[?1049l")
        val screen = terminal.snapshot()
        assertFalse(screen.alternateScreen)
        assertEquals(6, screen.columns)
        assertEquals(3, screen.rows)
        assertEquals("A", screen.lines[0][0].text)
        assertEquals("B", screen.lines[0][1].text)
        assertEquals(" ", screen.lines[0][2].text)
        assertEquals(" ", screen.lines[1][0].text)
        terminal.feed("\u001b[3;6HZ")
        assertEquals("Z", terminal.snapshot().lines[2][5].text)
    }

    @Test fun resetLeavesAlternateScreenAndDiscardsItsSavedScreen() {
        val terminal = XtermTerminal(4, 2)
        terminal.feed("old\u001b[?1049h")
        terminal.reset()
        assertFalse(terminal.snapshot().alternateScreen)
        terminal.feed("new\u001b[?1049hother\u001b[?1049l")
        assertEquals("new", terminal.plainText().substringBefore('\n'))
    }

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
