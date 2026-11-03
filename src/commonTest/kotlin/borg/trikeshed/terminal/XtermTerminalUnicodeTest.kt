package borg.trikeshed.terminal

import borg.trikeshed.lib.*
import kotlin.test.*

class XtermTerminalUnicodeTest {
    @Test fun splitSurrogateRetainsOneColoredWideGlyph() {
        val terminal = XtermTerminal(5, 2)
        terminal.feed("\u001b[38;5;196m\uD83D")
        assertEquals(" ", terminal.snapshot().lines[0][0].text)
        terminal.feed("\uDE80")
        val screen = terminal.snapshot()
        assertEquals("🚀", screen.lines[0][0].text)
        assertEquals(196, screen.lines[0][0].style.foreground.index)
        assertTrue(screen.lines[0][1].continuation)
        assertEquals(2, screen.cursor.column)
    }

    @Test fun combiningMarksAttachToWideAndRightEdgeGlyphs() {
        val terminal = XtermTerminal(5, 2)
        terminal.feed("\u001b[38;5;196mAB界")
        val before = terminal.revision
        terminal.feed("\u0301")
        var screen = terminal.snapshot()
        assertEquals("界\u0301", screen.lines[0][2].text)
        assertTrue(screen.lines[0][3].continuation)
        assertEquals(196, screen.lines[0][2].style.foreground.index)
        assertTrue(screen.revision > before)

        terminal.feed("界")
        screen = terminal.snapshot()
        assertEquals(" ", screen.lines[0][4].text)
        assertEquals("界", screen.lines[1][0].text)
        assertTrue(screen.lines[1][1].continuation)

        val edge = XtermTerminal(3, 2)
        edge.feed("abc\u0301")
        assertEquals("c\u0301", edge.snapshot().lines[0][2].text)
    }

    @Test fun resetDiscardsAnIncompleteSurrogate() {
        val terminal = XtermTerminal(5, 2)
        terminal.feed("\uD83D")
        terminal.reset()
        terminal.feed("A")
        assertEquals("A", terminal.snapshot().lines[0][0].text)
    }
}
