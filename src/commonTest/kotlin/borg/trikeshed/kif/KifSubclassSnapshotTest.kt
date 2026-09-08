package borg.trikeshed.kif

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KifSubclassSnapshotTest {
    @Test
    fun exportedBitsetsUseTheQueryIndexAndRevisionTracksOnlySubclassMutations() {
        val bank = KifKnowledgeBase()
        bank.assertKif("(subclass Child Parent)")
        bank.assertKif("(subclass Parent Entity)")
        val first = bank.subclassSnapshot()
        val child = first.nodes.single { it.name == "Child" }
        val namesById = first.nodes.associate { it.preorderId to it.name }
        assertEquals(setOf("Parent", "Entity"), child.ancestorIds.map { namesById[it] }.toSet())
        assertEquals(setOf("Parent", "Entity"), bank.query(KifExpr.parse("(subclass Child ?P)")).map { it["?P"] }.toSet())
        assertEquals(listOf("Child" to "Parent", "Parent" to "Entity"), first.directEdges)
        assertTrue(first.byteSize > 0)
        bank.assertKif("(kind x probe)")
        bank.assertKif("(subclass Child Parent)")
        assertEquals(first.revision, bank.subclassSnapshot().revision)
        bank.retractKif("(subclass Parent Entity)")
        val second = bank.subclassSnapshot()
        assertEquals(first.revision + 1, second.revision)
        assertEquals(setOf("Parent"), bank.query(KifExpr.parse("(subclass Child ?P)")).map { it["?P"] }.toSet())
        assertEquals(setOf("Parent", "Entity"), child.ancestorIds.map { namesById[it] }.toSet(), "old snapshots remain unchanged")
    }

    @Test
    fun emptyBankExportsNoInventedOntology() {
        val snapshot = KifKnowledgeBase().subclassSnapshot()
        assertEquals(emptyList(), snapshot.nodes)
        assertEquals(emptyList(), snapshot.directEdges)
        assertEquals(0, snapshot.byteSize)
    }
}
