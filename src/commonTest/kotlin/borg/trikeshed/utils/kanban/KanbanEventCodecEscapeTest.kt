package borg.trikeshed.utils.kanban

import borg.trikeshed.jules.JulesCause
import borg.trikeshed.jules.JulesSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `doc/todo.md`: "KanbanEventCodec decode parity: unescape JSON string content
 * (\n \t \" \\ \uXXXX) when decoding WAL records — card titles and cause
 * excerpts currently carry literal backslash-n because JsonParser.reify slices
 * raw escaped token chars."
 *
 * That task was still unchecked, and the file's own note says every unchecked
 * line is inducted and dispatched — so a stale entry is a standing order to
 * redo finished work. This test settles it with a round trip instead of an
 * opinion: encode a snapshot and a cause whose strings carry every escape the
 * task names, decode them back, and require the original characters.
 *
 * A title that survives this is a title that renders as a title on the board,
 * rather than showing a literal `\n` to whoever reads the card.
 */
class KanbanEventCodecEscapeTest {

    /** Every escape the task names, plus a quote-backslash adjacency that
     *  naive unescapers get wrong by consuming the wrong character. */
    private val nasty = "line1\nline2\ttabbed \"quoted\" back\\slash \\\" tricky ünïcode ✓ end"

    @Test
    fun snapshotTitleSurvivesEncodeDecode() {
        val snap = JulesSnapshot(
            sessionId = "sid-1",
            state = "IN_PROGRESS",
            title = nasty,
            patchBytes = 42L,
            headSha = "abc123",
            activeCount = 2,
            awaitingCount = 1,
            capturedAt = 1_700_000_000_000L,
        )
        val record = KanbanEventCodec.encodeSnapshot(snap, drained = false)

        // The WAL record itself must be one line of escaped JSON — a raw newline
        // in the record would split one event into two on replay.
        assertTrue('\n' !in record, "an encoded WAL record must not contain a raw newline")

        val decoded = assertIs<KanbanEventCodec.SnapEvent>(KanbanEventCodec.decode(record))
        assertEquals(nasty, decoded.snapshot.title, "the title must come back with real characters, not literal escapes")
        assertTrue('\n' in decoded.snapshot.title, "a newline must decode to a newline")
        assertTrue('\t' in decoded.snapshot.title, "a tab must decode to a tab")
        assertTrue("\"quoted\"" in decoded.snapshot.title, "quotes must survive")
        assertTrue("back\\slash" in decoded.snapshot.title, "a lone backslash must survive")
        assertEquals("sid-1", decoded.snapshot.sessionId)
        assertEquals(42L, decoded.snapshot.patchBytes)
    }

    @Test
    fun causeExcerptSurvivesEncodeDecode() {
        val cause = JulesCause.AgentMessaged(
            excerpt = nasty,
            at = 1_700_000_000_001L,
            activityId = "act-1",
            activitySeq = 3,
        )
        val record = KanbanEventCodec.encodeCause("sid-1", cause)
        assertTrue('\n' !in record, "an encoded WAL record must not contain a raw newline")

        val decoded = assertIs<KanbanEventCodec.CauseEvent>(KanbanEventCodec.decode(record))
        val back = assertIs<JulesCause.AgentMessaged>(decoded.cause)
        assertEquals(nasty, back.excerpt, "the excerpt must come back with real characters, not literal escapes")
        assertEquals("sid-1", decoded.sid)
        assertEquals("act-1", back.activityId)
        assertEquals(3, back.activitySeq)
    }

    @Test
    fun aTitleThatIsOnlyEscapesStillRoundTrips() {
        // The degenerate case: nothing but escape sequences, which is where an
        // index-walking unescaper runs off the end of the string.
        val only = "\\\n\t\"\\\\"
        val snap = JulesSnapshot("s", "S", only, 0L, "", 0, 0, 1L)
        val decoded = assertIs<KanbanEventCodec.SnapEvent>(
            KanbanEventCodec.decode(KanbanEventCodec.encodeSnapshot(snap, drained = true)),
        )
        assertEquals(only, decoded.snapshot.title)
        assertTrue(decoded.drained)
    }
}
