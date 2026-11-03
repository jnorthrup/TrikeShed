package borg.trikeshed.terminal

import borg.trikeshed.lib.*
import kotlin.test.*

class XtermTerminalUnicodeTest {
    @Test fun characterEditsDoNotLeaveHalfOfAWideGlyph() {
        val erased = XtermTerminal(5, 1)
        erased.feed("A界B\u001b[1;3H\u001b[X")
        var row = erased.snapshot().lines[0]
        assertEquals("A", row[0].text)
        assertEquals(" ", row[1].text)
        assertEquals(" ", row[2].text)
        assertEquals("B", row[3].text)

        val deleted = XtermTerminal(5, 1)
        deleted.feed("A界B\u001b[1;2H\u001b[P")
        row = deleted.snapshot().lines[0]
        assertEquals(" ", row[1].text)
        assertEquals("B", row[2].text)
        assertFalse(row[1].continuation)

        val inserted = XtermTerminal(5, 1)
        inserted.feed("A界B\u001b[1;3H\u001b[@")
        row = inserted.snapshot().lines[0]
        assertEquals(" ", row[1].text)
        assertEquals(" ", row[2].text)
        assertEquals("B", row[4].text)
        assertFalse(row[3].continuation)

        val clipped = XtermTerminal(4, 1)
        clipped.feed("AB界\u001b[1;1H\u001b[@")
        row = clipped.snapshot().lines[0]
        assertEquals("A", row[1].text)
        assertEquals("B", row[2].text)
        assertEquals(" ", row[3].text)

        val intact = XtermTerminal(6, 1)
        intact.feed("X界B\u001b[1;1H\u001b[P")
        row = intact.snapshot().lines[0]
        assertEquals("界", row[0].text)
        assertTrue(row[1].continuation)
        assertEquals("B", row[2].text)
    }

    @Test fun resizeDropsAWideGlyphClippedOnEitherScreen() {
        val terminal = XtermTerminal(4, 2)
        terminal.feed("AB界\u001b[?1049hAB界")
        terminal.resize(3, 2)
        assertEquals(" ", terminal.snapshot().lines[0][2].text)
        terminal.feed("\u001b[?1049l")
        assertEquals(" ", terminal.snapshot().lines[0][2].text)
        terminal.resize(4, 2)
        assertFalse(terminal.snapshot().lines[0][3].continuation)
    }

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
