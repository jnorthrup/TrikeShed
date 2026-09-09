package borg.trikeshed.hermes

import borg.trikeshed.lcnc.media.ManualMediaInput
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.terminal.VtCell
import borg.trikeshed.terminal.VtKey
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The server-side line discipline, exercised without booting GraalPy.
 *
 * A `commandSink` intercepts submission, so every assertion here is about what the operator sees on the
 * panel - the echoed line and the real terminal cursor - rather than about what Hermes would answer.
 */
class HermesConsoleLineEditorTest {

    /** The C0 controls a browser folds Ctrl-letter into, by code point so the source stays ASCII. */
    private val PROMPT = "hermes>"
    private val ctrlC = 3.toChar().toString()
    private val ctrlU = 21.toChar().toString()
    private val ctrlW = 23.toChar().toString()

    private val root = Files.createTempDirectory("hermes-line-editor")
    private val submitted = mutableListOf<String>()
    private val console = HermesVmConsole(root, root.resolve("sleeve"), columns = 40, rows = 8).apply {
        commandSink = { manual: ManualMediaInput -> submitted += manual.signal.payload }
    }

    @AfterTest fun tearDown() {
        console.close()
        Files.deleteIfExists(root)
    }

    /** The row the cursor is on, as plain text with trailing blanks trimmed. */
    private fun promptRow(): String {
        val snapshot = console.panel.terminal.snapshot(scrollbackRows = 0)
        val row = snapshot.lines[snapshot.cursor.row]
        return (0 until row.size).joinToString("") { i -> row[i].let { c: VtCell -> if (c.continuation) "" else c.text } }.trimEnd()
    }

    private fun cursorColumn(): Int = console.panel.terminal.snapshot(scrollbackRows = 0).cursor.column

    private fun type(text: String) = console.manualText(text)

    @Test
    fun typingEchoesOntoThePanelAndMovesTheRealCursor() {
        type("hello")
        assertEquals("hermes> hello", promptRow())
        assertEquals("hello" to 5, console.lineState())
        // 8 columns of prompt + 5 typed: the cursor an operator sees is the panel's, not a DOM caret.
        assertEquals(13, cursorColumn())
    }

    @Test
    fun backspaceAndInsertEditInPlace() {
        type("hello")
        console.manualKey(VtKey.BACKSPACE)
        assertEquals("hell", console.lineState().first)
        console.manualKey(VtKey.LEFT)
        console.manualKey(VtKey.LEFT)
        type("X")
        assertEquals("heXll", console.lineState().first)
        assertEquals("hermes> heXll", promptRow())
        assertEquals(11, cursorColumn(), "cursor sits after the inserted glyph, not at end of line")
    }

    @Test
    fun homeEndAndWordKillFollowReadline() {
        type("alpha beta")
        console.manualKey(VtKey.HOME)
        assertEquals(8, cursorColumn())
        console.manualKey(VtKey.END)
        assertEquals(18, cursorColumn())
        type(ctrlW)
        assertEquals("alpha ", console.lineState().first)
        type(ctrlU)
        assertEquals("", console.lineState().first)
    }

    @Test
    fun enterSubmitsTheLineAndClearsTheBuffer() {
        type("  :status  ")
        console.manualKey(VtKey.ENTER)
        assertEquals(listOf("  :status  "), submitted)
        assertEquals("" to 0, console.lineState())
    }

    @Test
    fun carriageReturnInsideTypedTextSubmitsExactlyOnce() {
        // A browser paste can carry CRLF; that is one submission, not two.
        type("one\r\ntwo")
        assertEquals(listOf("one"), submitted)
        assertEquals("two", console.lineState().first)
    }

    @Test
    fun historyRecallsPreviousLinesAndRestoresTheDraft() {
        type("first"); console.manualKey(VtKey.ENTER)
        type("second"); console.manualKey(VtKey.ENTER)
        type("draft")
        console.manualKey(VtKey.UP)
        assertEquals("second", console.lineState().first)
        console.manualKey(VtKey.UP)
        assertEquals("first", console.lineState().first)
        console.manualKey(VtKey.DOWN)
        console.manualKey(VtKey.DOWN)
        assertEquals("draft", console.lineState().first, "walking off the end restores what was being typed")
    }

    @Test
    fun blankLinesAreNotSubmittedAndNotRemembered() {
        console.manualKey(VtKey.ENTER)
        type("   "); console.manualKey(VtKey.ENTER)
        assertEquals(emptyList(), submitted)
        console.manualKey(VtKey.UP)
        assertEquals("", console.lineState().first)
    }

    @Test
    fun ctrlCAbandonsTheLine() {
        type("half typed")
        type(ctrlC)
        assertEquals("" to 0, console.lineState())
        assertEquals(emptyList(), submitted, "Ctrl-C abandons; it does not submit")
    }

    /** Every non-blank row of the screen, so a submit can be checked as a transcript, not one row. */
    private fun screen(): List<String> {
        val snapshot = console.panel.terminal.snapshot(scrollbackRows = 0)
        return (0 until snapshot.rows).map { r ->
            val row = snapshot.lines[r]
            (0 until row.size).joinToString("") { i -> row[i].let { c: VtCell -> if (c.continuation) "" else c.text } }.trimEnd()
        }
    }

    @Test
    fun aTurnsOutputStartsItsOwnRowRatherThanAppendingToThePrompt() {
        // The defect this guards: open() is reached from inside execute() when a first command boots a
        // closed console, and the prompt it drew there landed *before* the turn's output — so the screen
        // read "hermes> console unavailable: ..." (and, against a real VM, "hermes> :statusstate=ready").
        // One turn draws one prompt, and it is the last thing on the screen.
        console.commandSink = null                       // execute inline so the transcript is complete
        type(":status")
        console.manualKey(VtKey.ENTER)
        val rows = screen()
        val prompts = rows.filter { it.contains(PROMPT) }
        assertEquals(1, prompts.size, "one turn draws one prompt, got: $prompts")
        assertEquals(PROMPT, prompts.single().trim(), "the prompt row carries nothing but the prompt")
        assertTrue(
            rows.last { it.isNotBlank() }.contains(PROMPT),
            "the prompt is the last thing on screen, so the operator can type: ${rows.filter { it.isNotBlank() }}",
        )
    }

    @Test
    fun aLineLongerThanTheViewportScrollsInsteadOfWrapping() {
        // 40 columns, 8 of prompt: this line cannot fit, and must not spill onto the row below.
        val long = (1..90).joinToString("") { ('a' + (it % 26)).toString() }
        type(long)
        assertEquals(long, console.lineState().first)
        val cursor = console.panel.terminal.snapshot(scrollbackRows = 0).cursor
        assertTrue(cursor.column < 40, "cursor stayed inside the row: ${cursor.column}")
        assertTrue(promptRow().startsWith("hermes> "), "the prompt is still on the cursor's row, so nothing wrapped")
        // The tail is what an operator needs to see while typing.
        assertTrue(promptRow().endsWith(long.takeLast(5)), "window follows the cursor: '${promptRow()}'")
    }
}
