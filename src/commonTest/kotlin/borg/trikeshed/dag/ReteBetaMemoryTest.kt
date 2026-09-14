package borg.trikeshed.dag

import borg.trikeshed.cursor.blackboardContext
import borg.trikeshed.job.ContentId
import kotlin.test.Test
import kotlin.test.assertEquals
import borg.trikeshed.lib.map
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.lib.view

class ReteBetaMemoryTest {

    private fun fact(
        boardId: String,
        localId: String,
        fields: Map<String, Any?>,
        version: String = "$boardId:$localId:${fields.entries}",
    ): ReteStoredFact = ReteStoredFact(
        factId = FactId(boardId, localId),
        fields = fields,
        versionCid = ContentId.of(version.encodeToByteArray()),
        board = blackboardContext(id = boardId),
    )

    @Test
    fun equalityJoinIndexesBothSidesAndRemembersToken() {
        val beta = ReteBetaMemory(BetaJoin("dependsOn", "jobId"))
        val dependency = fact("board-a", "dep-1", mapOf("jobId" to "dep-1", "status" to "closed"))
        val depender = fact("board-a", "job-1", mapOf("jobId" to "job-1", "dependsOn" to "dep-1"))

        beta.acceptRight(dependency)
        beta.acceptLeft(depender)

        val token = beta.tokens().view.single()
        assertEquals(depender.factId, token.a.factId)
        assertEquals(dependency.factId, token.b.factId)
        assertEquals(token.a.fields["dependsOn"], token.b.fields["jobId"])
    }

    @Test
    fun equalJoinValuesNeverCrossBoardPartitions() {
        val beta = ReteBetaMemory(BetaJoin("dependsOn", "jobId"))

        beta.acceptRight(fact("board-b", "dep-1", mapOf("jobId" to "dep-1")))
        beta.acceptLeft(fact("board-a", "job-1", mapOf("dependsOn" to "dep-1")))

        assertEquals(0, beta.tokens().size)
    }

    @Test
    fun modifyAndRetractRemoveStaleJoinTokens() {
        val beta = ReteBetaMemory(BetaJoin("dependsOn", "jobId"))
        val dep1 = fact("board-a", "dep-1", mapOf("jobId" to "dep-1"))
        val dep2 = fact("board-a", "dep-2", mapOf("jobId" to "dep-2"))
        val initial = fact("board-a", "job-1", mapOf("dependsOn" to "dep-1"), "v1")

        beta.acceptRight(dep1)
        beta.acceptRight(dep2)
        beta.acceptLeft(initial)
        assertEquals(listOf("dep-1"), beta.tokens().map { it.b.fields["jobId"] })

        beta.acceptLeft(initial.copy(
            factId = FactId("board-a", "job-1"),
            fields = mapOf("dependsOn" to "dep-2"),
            versionCid = ContentId.of("v2".encodeToByteArray()),
        ))
        assertEquals(listOf("dep-2"), beta.tokens().map { it.b.fields["jobId"] })

        beta.retractRight(FactId("board-a", "dep-2"))
        assertEquals(0, beta.tokens().size)
    }

    @Test
    fun replacingAndRetractingOneFactPreservesOtherMatchesAndEarlierSnapshots() {
        val beta = ReteBetaMemory(BetaJoin("dependsOn", "jobId"))
        val leftA = fact("p", "a", mapOf("dependsOn" to "shared"))
        val leftB = fact("p", "b", mapOf("dependsOn" to "shared"))
        val rightA = fact("p", "x", mapOf("jobId" to "shared"))
        val rightB = fact("p", "y", mapOf("jobId" to "shared"))
        beta.acceptLeft(leftA)
        beta.acceptLeft(leftB)
        beta.acceptRight(rightA)
        beta.acceptRight(rightB)
        val before = beta.tokens()
        assertEquals(4, before.size)

        val changed = rightA.copy(factId = FactId("p", "x"), versionCid = ContentId.of("v2".encodeToByteArray()))
        beta.acceptRight(changed)
        assertEquals(4, beta.tokens().size)
        assertEquals(listOf(changed.versionCid, rightB.versionCid, changed.versionCid, rightB.versionCid),
            beta.tokens().map { it.b.versionCid })

        beta.retractLeft(FactId("p", "a"))
        assertEquals(listOf("b" to "x", "b" to "y"), beta.tokens().map { it.a.factId.b to it.b.factId.b })
        beta.retractRight(FactId("p", "y"))
        assertEquals(listOf("b" to "x"), beta.tokens().map { it.a.factId.b to it.b.factId.b })
        assertEquals(4, before.size)
        assertEquals(rightA.versionCid, before[0].b.versionCid)
    }

    @Test
    fun unrelatedGroupsAndUnmatchedFactsStaySeparate() {
        val beta = ReteBetaMemory(BetaJoin("dependsOn", "jobId"))
        for (i in 0 until 12) {
            beta.acceptLeft(fact("p", "left-$i", mapOf("dependsOn" to "group-$i")))
            beta.acceptRight(fact("p", "right-$i", mapOf("jobId" to "group-$i")))
        }
        beta.acceptLeft(fact("p", "unmatched", mapOf("dependsOn" to "absent")))
        beta.acceptRight(fact("other", "foreign", mapOf("jobId" to "group-0")))
        val matches = beta.tokens()
        assertEquals(12, matches.size)
        for (token in matches.view) {
            assertEquals("p", token.a.factId.a)
            assertEquals("p", token.b.factId.a)
            assertEquals(token.a.fields["dependsOn"], token.b.fields["jobId"])
        }
    }
}
