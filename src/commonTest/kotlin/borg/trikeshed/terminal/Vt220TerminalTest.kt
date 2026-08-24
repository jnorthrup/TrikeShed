package borg.trikeshed.terminal

import borg.trikeshed.context.lcnc.CausalMark
import borg.trikeshed.lcnc.media.CausalSignalKind
import borg.trikeshed.lcnc.media.LcncSignalLane
import borg.trikeshed.lcnc.media.MediaPatchPanelDescriptor
import borg.trikeshed.lcnc.media.MediaPatchPanelId
import borg.trikeshed.lcnc.media.Vt220MediaPatchPanel
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.lib.view
import borg.trikeshed.userspace.reactor.KanbanEvent
import borg.trikeshed.userspace.reactor.KanbanFSM
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Vt220TerminalTest {
    @AfterTest
    fun resetKanban() = KanbanFSM.reset()

    @Test
    fun cursorEraseSgrAndDeviceRepliesBehaveAsVt220() {
        val vt = Vt220Terminal(12, 3)
        vt.drainPatches()
        val patches = vt.feed("hello\u001b[2;3H\u001b[31;1mX\u001b[0m\u001b[5n\u001b[6n\u001bZ")
        val screen = vt.snapshot()

        assertEquals("hello", screen.lines[0].view.joinToString("") { it.text }.trimEnd())
        assertEquals("X", screen.lines[1][2].text)
        assertEquals(1, screen.lines[1][2].style.foreground.index)
        assertTrue(screen.lines[1][2].style.bold)
        assertTrue(patches.size > 0)
        assertEquals("\u001b[0n\u001b[2;4R\u001b[?62;1;2;6;7;8;9c", vt.consumeReplies())
    }

    @Test
    fun alternateScreenScrollbackUnicodeAndResizePreserveTerminalState() {
        val vt = Vt220Terminal(6, 2, scrollbackLimit = 4)
        vt.drainPatches()
        vt.feed("main")
        vt.feed("\u001b[?1049hALT\u001b[?1049l")
        assertTrue(vt.plainText().startsWith("main"))
        assertFalse(vt.snapshot().alternateScreen)

        vt.feed("\r\n一🙂\r\nlast")
        val before = vt.snapshot()
        assertTrue(before.scrollback.size > 0)
        assertTrue(before.lines.view.any { row -> row.view.any { it.continuation } })

        val resize = vt.resize(9, 4)
        assertTrue(resize.size >= 4)
        assertEquals(9, vt.snapshot().columns)
        assertEquals(4, vt.snapshot().rows)
    }

    @Test
    fun applicationCursorModeChangesManualArrowEncoding() {
        val vt = Vt220Terminal()
        vt.drainPatches()
        assertEquals("\u001b[A", vt.encode(VtKey.UP))
        vt.feed("\u001b[?1h")
        assertEquals("\u001bOA", vt.encode(VtKey.UP))
        vt.feed("\u001b[?1l")
        assertEquals("\u001b[A", vt.encode(VtKey.UP))
    }

    @Test
    fun mediaPanelBondsManualInputToCausalPatchesAndLcncMarks() {
        val panel = Vt220MediaPatchPanel(
            MediaPatchPanelDescriptor(MediaPatchPanelId("hermes/vt220"), "vt220", "Hermes", 20, 4),
        )
        panel.terminal.drainPatches()
        val manual = panel.manualCommand("status", timestampMs = 10)
        val output = panel.causalOutput("\u001b[32mready\u001b[0m", manual.signal.id, timestampMs = 11)

        assertEquals(LcncSignalLane.MANUAL, manual.signal.lane)
        assertEquals(LcncSignalLane.CAUSAL, output.signal.lane)
        assertEquals(manual.signal.id, output.signal.causeSignalId)
        assertTrue(output.patches.size > 0)
        assertTrue(output.patches.view.all { it.causeSignalId == manual.signal.id })
        assertEquals(CausalMark.Inducted, manual.signal.marked().a.a.b)
        assertEquals(CausalMark.Answered, output.signal.marked().a.a.b)
        assertEquals(2, panel.signals().size)

        val state1 = KanbanFSM.reduce(manual.signal.toEvent())
        val state2 = KanbanFSM.reduce(output.signal.toEvent(), state1)
        assertEquals(1, state2.manualSignalCount)
        assertEquals(1, state2.causalSignalCount)
        assertEquals(output.signal.id, state2.lastLcncSignalId)
    }

    private fun borg.trikeshed.lcnc.media.LcncUserSignal.toEvent() = KanbanEvent.LcncUserSignaled(
        panelId = panelId.value,
        signalId = id,
        lane = lane.name.lowercase(),
        kind = kind,
        causeSignalId = causeSignalId,
        payload = payload,
        timestampMs = timestampMs,
    )
}
