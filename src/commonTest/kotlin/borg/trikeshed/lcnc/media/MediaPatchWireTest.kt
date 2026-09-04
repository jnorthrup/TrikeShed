package borg.trikeshed.lcnc.media

import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.get
import borg.trikeshed.lib.toSeries
import borg.trikeshed.terminal.VtCell
import borg.trikeshed.terminal.VtColor
import borg.trikeshed.terminal.VtStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MediaPatchWireTest {

    @Test
    fun testTerminalCellsPatchRoundtrip() {
        val cell1 = VtCell(text = "A", style = VtStyle(foreground = VtColor(index = 1)))
        val cell2 = VtCell(text = "B", style = VtStyle(foreground = VtColor(index = 2), bold = true))
        val cells = listOf(cell1, cell2).toSeries()
        val payload = MediaPatchPayload.TerminalCells(cells = cells)
        val patch = MediaPatch(
            panelId = MediaPatchPanelId("p1"),
            kind = MediaPatchKind.TERMINAL_CELLS,
            revision = 42L,
            x = 0,
            y = 0,
            width = 80,
            height = 24,
            payload = payload,
            causeSignalId = "sig-1",
        )

        val map = patch.toMap()
        assertEquals("p1", map["panelId"])
        assertEquals("terminal_cells", map["kind"])
        assertEquals(42L, map["revision"])
        assertEquals(0, map["x"])
        assertEquals(0, map["y"])
        assertEquals(80, map["width"])
        assertEquals(24, map["height"])
        assertEquals("sig-1", map["causeSignalId"])

        val deserialized = MediaPatch.fromMap(map)
        assertNotNull(deserialized)
        assertEquals(patch.panelId, deserialized.panelId)
        assertEquals(patch.kind, deserialized.kind)
        assertEquals(patch.revision, deserialized.revision)
        assertEquals(patch.causeSignalId, deserialized.causeSignalId)
        assertTrue(deserialized.payload is MediaPatchPayload.TerminalCells)

        val deserPayload = deserialized.payload as MediaPatchPayload.TerminalCells
        assertEquals(2, deserPayload.cells.size)
        assertEquals("A", deserPayload.cells[0].text)
        assertEquals("B", deserPayload.cells[1].text)
        assertEquals(1, deserPayload.cells[0].style.foreground.index)
        assertEquals(true, deserPayload.cells[1].style.bold)
    }

    @Test
    fun testTextPatchRoundtrip() {
        val patch = MediaPatch(
            panelId = MediaPatchPanelId("p-text"),
            kind = MediaPatchKind.TEXT,
            revision = 10L,
            x = 0,
            y = 0,
            width = 100,
            height = 1,
            payload = MediaPatchPayload.Text("System ready\nProcessing stream..."),
            causeSignalId = "sig-log-42",
        )

        val map = patch.toMap()
        assertEquals("p-text", map["panelId"])
        assertEquals("text", map["kind"])
        assertEquals("System ready\nProcessing stream...", map["text"])

        val deserialized = MediaPatch.fromMap(map)
        assertNotNull(deserialized)
        assertEquals("p-text", deserialized.panelId.value)
        assertEquals("sig-log-42", deserialized.causeSignalId)
        assertTrue(deserialized.payload is MediaPatchPayload.Text)
        assertEquals("System ready\nProcessing stream...", (deserialized.payload as MediaPatchPayload.Text).value)
    }

    @Test
    fun testBytesPatchRoundtrip() {
        val bytes = byteArrayOf(0x1F, 0x8B.toByte(), 0x08, 0x00)
        val patch = MediaPatch(
            panelId = MediaPatchPanelId("p-bytes"),
            kind = MediaPatchKind.AUDIO_RANGE,
            revision = 5L,
            x = 0,
            y = 0,
            width = 0,
            height = 0,
            payload = MediaPatchPayload.Bytes(
                contentType = "audio/ogg",
                bytes = bytes,
            ),
            causeSignalId = null,
        )

        val map = patch.toMap()
        assertEquals("p-bytes", map["panelId"])
        assertEquals("audio_range", map["kind"])
        assertEquals("audio/ogg", map["contentType"])

        val deserialized = MediaPatch.fromMap(map)
        assertNotNull(deserialized)
        assertEquals("p-bytes", deserialized.panelId.value)
        assertTrue(deserialized.payload is MediaPatchPayload.Bytes)
        val deserPayload = deserialized.payload as MediaPatchPayload.Bytes
        assertEquals("audio/ogg", deserPayload.contentType)
        assertEquals(4, deserPayload.bytes.size)
        assertEquals(0x1F, deserPayload.bytes[0])
    }

    @Test
    fun testLcncUserSignalRoundtrip() {
        val signal = LcncUserSignal(
            id = "user-sig-99",
            panelId = MediaPatchPanelId("p-sig"),
            sequence = 7L,
            timestampMs = 55667788L,
            lane = LcncSignalLane.MANUAL,
            kind = "key_press",
            payload = """{"key":"Enter","ctrl":true}""",
            causeSignalId = "cause-1",
        )

        val map = signal.toMap()
        assertEquals("user-sig-99", map["id"])
        assertEquals("p-sig", map["panelId"])
        assertEquals(7L, map["sequence"])
        assertEquals(55667788L, map["timestampMs"])
        assertEquals("manual", map["lane"])
        assertEquals("key_press", map["kind"])
        assertEquals("cause-1", map["causeSignalId"])

        val deserialized = LcncUserSignal.fromMap(map)
        assertNotNull(deserialized)
        assertEquals(signal.id, deserialized.id)
        assertEquals(signal.panelId, deserialized.panelId)
        assertEquals(signal.sequence, deserialized.sequence)
        assertEquals(signal.timestampMs, deserialized.timestampMs)
        assertEquals(signal.lane, deserialized.lane)
        assertEquals(signal.kind, deserialized.kind)
        assertEquals(signal.payload, deserialized.payload)
        assertEquals(signal.causeSignalId, deserialized.causeSignalId)
    }
}
