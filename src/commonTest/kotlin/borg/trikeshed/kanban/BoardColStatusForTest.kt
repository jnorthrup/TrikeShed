package borg.trikeshed.kanban

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Plan step 3 rot-burn gate: the MoveCard status fold has ONE author —
 * BoardCol.statusFor. The legacy switch in ArticulatedNode.applySignal let
 * every canonical column id except `col-inprogress`/`col-done` fall through
 * to "backlog"; the fold here must cover all three vocabularies.
 */
class BoardColStatusForTest {

    @Test
    fun canonicalProjectionIdsFoldCorrectly() {
        assertEquals("in-progress", BoardCol.statusFor("col-inprogress"))
        assertEquals("done", BoardCol.statusFor("col-done"))
        assertEquals("backlog", BoardCol.statusFor("col-backlog"))
    }

    @Test
    fun wireVocabularyFoldsCorrectly() {
        // the seven wire ids — the ones the old switch silently misfiled
        assertEquals("backlog", BoardCol.statusFor("triage"))
        assertEquals("backlog", BoardCol.statusFor("todo"))
        assertEquals("backlog", BoardCol.statusFor("ready"))
        assertEquals("in-progress", BoardCol.statusFor("running"))
        assertEquals("backlog", BoardCol.statusFor("blocked"), "blocked is not backlog-in-progress — coarse fold keeps it parked")
        assertEquals("done", BoardCol.statusFor("done"))
        assertEquals("done", BoardCol.statusFor("archived"))
    }

    @Test
    fun legacyColFoldIntoCanonical() {
        // the JobProjection col-* four must never crash the fold
        assertEquals("backlog", BoardCol.statusFor("col-causal-blocked"))
        assertEquals("in-progress", BoardCol.statusFor("col-agentic"))
        assertEquals("backlog", BoardCol.statusFor("col-attention"))
        assertEquals("done", BoardCol.statusFor("col-closed"))
    }

    @Test
    fun unknownColumnFallsBackToBacklogLikeTheOldSwitch() {
        assertEquals("backlog", BoardCol.statusFor("col-b"))
        assertEquals("backlog", BoardCol.statusFor("who-knows"))
    }

    @Test
    fun foldAgreesWithLegacyFoldWhereItWasRight() {
        // the old switch's two correct entries stay correct (no behaviour change)
        assertEquals("in-progress", BoardCol.statusFor("col-inprogress"))
        assertEquals("done", BoardCol.statusFor("col-done"))
    }
}
