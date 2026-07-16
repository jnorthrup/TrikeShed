package borg.trikeshed.dag

import borg.trikeshed.cursor.blackboardContext
import borg.trikeshed.job.ContentId
import kotlin.test.Test
import kotlin.test.assertEquals

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

        val token = beta.tokens().single()
        assertEquals(depender.factId, token.left.factId)
        assertEquals(dependency.factId, token.right.factId)
        assertEquals("dep-1", token.joinValue)
    }

    @Test
    fun equalJoinValuesNeverCrossBoardPartitions() {
        val beta = ReteBetaMemory(BetaJoin("dependsOn", "jobId"))

        beta.acceptRight(fact("board-b", "dep-1", mapOf("jobId" to "dep-1")))
        beta.acceptLeft(fact("board-a", "job-1", mapOf("dependsOn" to "dep-1")))

        assertEquals(emptyList(), beta.tokens())
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
        assertEquals(listOf("dep-1"), beta.tokens().map { it.joinValue })

        beta.acceptLeft(initial.copy(
            fields = mapOf("dependsOn" to "dep-2"),
            versionCid = ContentId.of("v2".encodeToByteArray()),
        ))
        assertEquals(listOf("dep-2"), beta.tokens().map { it.joinValue })

        beta.retractRight(dep2.factId)
        assertEquals(emptyList(), beta.tokens())
    }
}
