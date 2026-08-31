package borg.trikeshed.kanban

import borg.trikeshed.job.ContentId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The same double-unescape that corrupted [borg.trikeshed.utils.kanban.KanbanEventCodec],
 * found in the board's source envelope — where it is not cosmetic.
 *
 * `decode` recomputes `ContentId.of(description)` and `require`s it to equal the
 * stored `contentId`. `JsonSupport.parse` has already decoded the string, so a
 * second `jsonUnescape` pass changed the description's bytes, the content id no
 * longer matched, and the envelope failed to load AT ALL with a mismatch error.
 *
 * Markdown is exactly the payload that carries backslashes — a fenced code
 * block, a Windows path, a regex — so this is reachable from ordinary content
 * rather than an adversarial string.
 */
class ForgeBoardPersistenceEscapeTest {

    private fun source(description: String) = ForgeKanbanSource(
        // The envelope version is private to ForgeBoardPersistence and `decode`
        // rejects anything else. Spelling the literal here keeps the constant
        // private rather than widening production visibility for a test — if the
        // version is ever bumped, this test failing IS the reminder to look.
        version = 1,
        userId = "jim",
        title = "Board \"quoted\" title",
        sourcePath = "C:\\notes\\plan.md",
        description = description,
        contentId = ContentId.of(description.encodeToByteArray()).value,
    )

    @Test
    fun aDescriptionCarryingBackslashesRoundTripsAndKeepsItsContentId() {
        // Ordinary markdown: a regex in a fence, a quoted phrase, a tab, a path.
        val markdown = """
            # Plan

            Match with `\d+\.\d+` and escape a quote as \" in the fence:

            ```
            printf "a\tb\n"
            C:\Users\jim\work
            ```
        """.trimIndent()

        val decoded = ForgeBoardPersistence.decode(ForgeBoardPersistence.encode(source(markdown)))
        assertEquals(markdown, decoded.description, "the description must survive byte-for-byte")
        assertEquals(
            ContentId.of(markdown.encodeToByteArray()).value,
            decoded.contentId,
            "the content id must still address the decoded bytes",
        )
        assertEquals("C:\\notes\\plan.md", decoded.sourcePath, "a Windows path must keep its separators")
        assertEquals("Board \"quoted\" title", decoded.title)
    }

    @Test
    fun theDegenerateAllEscapesDescriptionStillLoads() {
        val nasty = "\\\" \\\\ \\n literal, and real:\n\ttab"
        val decoded = ForgeBoardPersistence.decode(ForgeBoardPersistence.encode(source(nasty)))
        assertEquals(nasty, decoded.description)
    }
}
