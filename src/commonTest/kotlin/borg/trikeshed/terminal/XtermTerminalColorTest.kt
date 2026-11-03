package borg.trikeshed.terminal

import borg.trikeshed.lib.*
import kotlin.test.*

class XtermTerminalColorTest {
    @Test fun colonAndMixedColorParametersPreserveOtherAttributes() {
        val terminal = XtermTerminal(8, 2)
        terminal.feed("\u001b[1;38:5:196;48:5:24mA")
        terminal.feed("\u001b[38:2::4:5:6;48:2:20:30:40mB")
        terminal.feed("\u001b[38;5:201;48;2::7:8:9mC")
        val cells = terminal.snapshot().lines[0]
        assertTrue(cells[0].style.bold)
        assertEquals(196, cells[0].style.foreground.index)
        assertEquals(24, cells[0].style.background.index)
        assertEquals(0x040506, cells[1].style.foreground.rgb)
        assertEquals(0x141e28, cells[1].style.background.rgb)
        assertTrue(cells[1].style.bold)
        assertEquals(201, cells[2].style.foreground.index)
        assertEquals(0x070809, cells[2].style.background.rgb)
    }

    @Test fun malformedColorSubparametersDoNotResetTheCurrentStyle() {
        val terminal = XtermTerminal(4, 2)
        terminal.feed("\u001b[1;31mA\u001b[38:5mB\u001b[48:2::1:2mC")
        val cells = terminal.snapshot().lines[0]
        for (i in 0..2) {
            assertEquals(1, cells[i].style.foreground.index)
            assertTrue(cells[i].style.bold)
        }
    }

    @Test fun changingColorDoesNotCancelPendingWrap() {
        val terminal = XtermTerminal(3, 2)
        terminal.feed("abc\u001b[38;5;196mD")
        val screen = terminal.snapshot()
        assertEquals("c", screen.lines[0][2].text)
        assertEquals("D", screen.lines[1][0].text)
        assertEquals(196, screen.lines[1][0].style.foreground.index)
    }
}
