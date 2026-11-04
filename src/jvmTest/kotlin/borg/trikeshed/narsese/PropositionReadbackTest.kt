package borg.trikeshed.narsese

import borg.trikeshed.cursor.BudgetCoord
import borg.trikeshed.cursor.columnNames
import borg.trikeshed.cursor.values
import borg.trikeshed.forge.server.BeliefWire
import borg.trikeshed.job.*
import borg.trikeshed.lcnc.LcncNode
import borg.trikeshed.lib.*
import borg.trikeshed.parse.reify
import kotlinx.coroutines.*
import kotlin.test.*

class PropositionReadbackTest {
    @Test fun cursorLcncAndHttpExposeOptionalIdentityWithoutChangingEvidence() = runBlocking {
        withTimeout(10_000) {
            val bag = BeliefBagElement(parentJob = currentCoroutineContext()[Job]); bag.open()
            val review = TurnReviewElement(bag); review.open()
            try {
                val source = ContentId.of("synthetic-readback-source".encodeToByteArray())
                val known = KgNalBridge.map(KgTriplet("Tenant", "pays", "Landlord")).signal(source.value)
                val legacy = known.copy(angular = known.angular xor 1L, propositionCid = null)
                for (signal in s_[known, legacy].view) bag.submit(BeliefIntake.Mint(signal,
                    BudgetCoord(.8f, .8f, .5f), source, EvidenceBasis.of(source)))
                val before = bag.snapshot().values.associate { it.angular to SignalCodec.encode(it) }
                val cursor = bag.cursor()
                val oldColumns = s_["angular", "frequency", "confidence", "priority", "durability", "quality",
                    "relation", "subjectCid", "objectCid", "provenanceCid", "gloss"]
                assertEquals(oldColumns.size + 1, cursor.columnNames.size)
                for (i in 0 until oldColumns.size) assertEquals(oldColumns[i], cursor.columnNames[i].toString())
                assertEquals("propositionCid", cursor.columnNames[oldColumns.size].toString())
                for (i in 0 until cursor.size) {
                    val row = cursor[i].values
                    val angular = (row[0] as Number).toLong()
                    assertEquals(bag.signalOf(angular)?.propositionCid, row[oldColumns.size])
                }
                fun checked(rows: List<*>, angular: String) {
                    assertTrue(rows.isNotEmpty())
                    for (raw in rows) {
                        val row = raw as Map<*, *>
                        val id = row[angular].toString().toLong()
                        assertTrue(row.containsKey("propositionCid"))
                        assertEquals(bag.signalOf(id)?.propositionCid, row["propositionCid"])
                        assertTrue(row["propositionCid"] == null || row["propositionCid"] is String)
                    }
                }
                for (mode in s_["top", "near", "sample"].view) {
                    val output = NalNodes.recallRunner(bag).run(LcncNode("recall", "nal.recall", _m[
                        "mode" j mode, "k" j "64", "centroid" j known.angular.toString(), "maxDistance" j "64"]), emptyMap())
                    checked(output["beliefs"] as List<*>, "angular")
                }
                val response = requireNotNull(BeliefWire(bag, review, null).route("GET", "/api/beliefs", "", null))
                assertEquals(200, response.status)
                checked((reify(response.body) as Map<*, *>)["beliefs"] as List<*>, "angular")
                for ((angular, bytes) in before) assertContentEquals(bytes, SignalCodec.encode(requireNotNull(bag.signalOf(angular))))
            } finally {
                review.drain(); bag.drain()
                assertTrue(bag.worker?.isCompleted == true && bag.supervisor.isCompleted)
            }
        }
    }
}
