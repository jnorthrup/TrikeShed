package borg.trikeshed.terminal

import borg.trikeshed.lib.*
import kotlin.test.*

class XtermTerminalEraseSavedTest {
    private fun assertScreen(expected: VtSnapshot, actual: VtSnapshot) {
        assertEquals(expected.cursor, actual.cursor)
        assertEquals(expected.rows, actual.rows)
        assertEquals(expected.columns, actual.columns)
        for (r in 0 until expected.rows) {
            assertEquals(expected.rowIds[r], actual.rowIds[r])
            for (c in 0 until expected.columns) assertEquals(expected.lines[r][c], actual.lines[r][c])
        }
    }

    @Test fun eraseSavedLinesKeepsVisibleScreenAndStyles() {
        val terminal = XtermTerminal(8, 2, scrollbackLimit = 5)
        terminal.feed("history\r\n\u001b[38;5;196mred\r\nblue")
        val before = terminal.snapshot()
        assertEquals(1, before.scrollback.size)
        assertEquals(VtColor(196), before.lines[0][0].style.foreground)
        val patches = terminal.feed("\u001b[3J")
        val after = terminal.snapshot()
        assertEquals(0, after.scrollback.size)
        assertEquals(0, after.scrollbackIds.size)
        assertScreen(before, after)
        assertEquals(0, patches.size, "erasing history must not repaint visible cells")
        assertTrue(after.revision > before.revision, "history removal must be observable")
    }

    @Test fun eraseVisibleScreenKeepsSavedLines() {
        val terminal = XtermTerminal(8, 2, scrollbackLimit = 5)
        terminal.feed("history\r\nmain\r\nmore")
        val before = terminal.snapshot()
        assertEquals(1, before.scrollback.size)
        terminal.feed("\u001b[2J")
        val after = terminal.snapshot()
        assertEquals(before.scrollbackIds[0], after.scrollbackIds[0])
        assertEquals(before.scrollback[0][0], after.scrollback[0][0])
        for (r in 0 until after.rows) {
            assertEquals(before.rowIds[r], after.rowIds[r])
            for (c in 0 until after.columns) assertEquals(" ", after.lines[r][c].text)
        }
    }

    @Test fun eraseSavedLinesOnAlternateScreenKeepsMainHistory() {
        val terminal = XtermTerminal(8, 2, scrollbackLimit = 5)
        terminal.feed("history\r\nmain\r\nmore")
        val main = terminal.snapshot()
        assertEquals(1, main.scrollback.size)
        terminal.feed("\u001b[?1049h\u001b[48;5;27malternate")
        val alternate = terminal.snapshot()
        terminal.feed("\u001b[3J")
        val cleared = terminal.snapshot()
        assertScreen(alternate, cleared)
        assertEquals(main.scrollbackIds[0], cleared.scrollbackIds[0])
        assertEquals(main.scrollback[0][0], cleared.scrollback[0][0])
        terminal.feed("\u001b[?1049l")
        val restored = terminal.snapshot()
        assertScreen(main, restored)
        assertEquals(main.scrollbackIds[0], restored.scrollbackIds[0])
    }
}
