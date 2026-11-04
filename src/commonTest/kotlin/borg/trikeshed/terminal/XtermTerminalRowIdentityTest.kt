package borg.trikeshed.terminal

import borg.trikeshed.lib.*
import kotlin.test.*

class XtermTerminalRowIdentityTest {
    private fun Series<Long>.values() = (0 until size).map { this[it] }

    @Test fun rowsMoveIntoHistoryAndEvictionNeverReusesIdentity() {
        val terminal = XtermTerminal(4, 2, scrollbackLimit = 2)
        val initial = terminal.snapshot()
        val rows = initial.rowIds.values()
        assertEquals(2, rows.distinct().size)
        terminal.feed("A\u001b[2J\u001b[1S")
        val first = terminal.snapshot()
        assertEquals(listOf(rows[0]), first.scrollbackIds.values())
        assertEquals(rows[1], first.rowIds[0])
        assertEquals(rows, initial.rowIds.values(), "snapshots must retain captured identities")
        terminal.feed("\u001b[3S")
        val later = terminal.snapshot()
        val retained = later.scrollbackIds.values() + later.rowIds.values()
        assertEquals(4, retained.distinct().size)
        assertTrue(retained.none { it in rows })
        terminal.feed("\u001b[3J\u001b[1S")
        val cleared = terminal.snapshot()
        assertEquals(1, cleared.scrollbackIds.size)
        assertEquals(later.rowIds[1], cleared.rowIds[0])
        assertTrue(cleared.rowIds[1] !in retained)
    }

    @Test fun alternateScreenResizeAndLineOperationsMoveRows() {
        val terminal = XtermTerminal(4, 3)
        val original = terminal.snapshot().rowIds.values()
        terminal.feed("\u001b[2;1H\u001b[1L")
        val inserted = terminal.snapshot().rowIds.values()
        assertEquals(original[0], inserted[0])
        assertEquals(original[1], inserted[2])
        terminal.feed("\u001b[1M")
        assertEquals(original[1], terminal.snapshot().rowIds[1])
        val main = terminal.snapshot().rowIds.values()
        terminal.feed("\u001b[?1049h")
        val alternate = terminal.snapshot().rowIds.values()
        assertTrue(alternate.none { it in main })
        terminal.resize(4, 4)
        terminal.feed("\u001b[?1049l")
        val restored = terminal.snapshot().rowIds.values()
        assertEquals(main, restored.take(3))
        assertTrue(restored.last() !in main && restored.last() !in alternate)
    }

    @Test fun resetAndPartialScrollingNeverReuseRows() {
        val terminal = XtermTerminal(4, 4, scrollbackLimit = 3)
        val initial = terminal.snapshot().rowIds.values()
        terminal.feed("\u001b[2;4r\u001b[1S")
        val scrolled = terminal.snapshot().rowIds.values()
        assertEquals(initial[0], scrolled[0])
        assertEquals(initial[2], scrolled[1])
        assertEquals(initial[3], scrolled[2])
        assertTrue(scrolled[3] !in initial)
        terminal.reset()
        val reset = terminal.snapshot().rowIds.values()
        assertTrue(reset.none { it in initial || it in scrolled })
        terminal.feed("\u001b[1S")
        val captured = terminal.snapshot(1)
        terminal.feed("\u001b[1S")
        assertEquals(listOf(reset[0]), captured.scrollbackIds.values())
        assertEquals(listOf(reset[1]), terminal.snapshot(1).scrollbackIds.values())
    }

    @Test fun optimizedScrollPreservesTheSameIdentitiesAsIndividualScrolls() {
        fun run(large: Boolean, down: Boolean): VtSnapshot {
            val terminal = XtermTerminal(3, 2, scrollbackLimit = 3)
            if (large) terminal.feed(if (down) "\u001b[12T" else "\u001b[12S")
            else repeat(12) { terminal.feed(if (down) "\u001b[T" else "\u001b[S") }
            return terminal.snapshot()
        }
        val huge = XtermTerminal(3, 2, scrollbackLimit = 3)
        huge.feed("\u001b[2147483647S")
        val hugeRows = huge.snapshot()
        assertEquals(listOf(2147483644L, 2147483645L, 2147483646L), hugeRows.scrollbackIds.values())
        assertEquals(listOf(2147483647L, 2147483648L), hugeRows.rowIds.values())
        for (down in listOf(false, true)) {
            val fast = run(true, down)
            val slow = run(false, down)
            assertEquals(slow.rowIds.values(), fast.rowIds.values())
            assertEquals(slow.scrollbackIds.values(), fast.scrollbackIds.values())
        }
    }
}
