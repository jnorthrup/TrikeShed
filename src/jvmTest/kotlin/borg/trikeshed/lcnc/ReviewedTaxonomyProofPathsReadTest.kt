package borg.trikeshed.lcnc

import borg.trikeshed.lib.*
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ReviewedTaxonomyProofPathsReadTest {
    @org.junit.jupiter.api.io.TempDir lateinit var directory: Path
    private fun rows(value: Any?) = (value as List<*>).map { it as Map<*, *> }

    @Test fun protectedReportResolvesOffPagePremisesAndRefusesStaleOrWithdrawnApproval(): Unit = runBlocking {
        val f = EnglishTaxonomyExportGuardTest.Fixture(directory)
        val candidate = f.candidate("three", "(instance Tenant Person)\n(subclass Person Agent)\n(subclass Other Unrelated)")
        f.ledger(this) { review ->
            val approved = f.approve(review, candidate)
            withContext(f.actor) {
                suspend fun page(offset: Int) = ReviewedTaxonomyPlan.report(review, f.cas,
                    approved.snapshotCid, approved.revision, "instance", "Tenant", "Agent", offset, 1)
                val before = Files.size(f.wal)
                val first = page(0); val third = page(2)
                assertEquals("SUPPORTED", third["status"])
                assertEquals("(subclass Other Unrelated)", (rows(third["selected"]).single()["quotation"] as Map<*, *>)["quote"])
                val plan = third["plan"] as Map<*, *>
                assertEquals(first["plan"], plan)
                assertNotEquals(first["readFrameCid"], third["readFrameCid"])
                val byNode = rows(plan["sources"]).associateBy { it["nodeId"] }
                assertEquals(3, byNode.size)
                val proof = rows(plan["proofs"]).single()
                assertEquals(_l["(instance Tenant Person)", "(subclass Person Agent)"],
                    (proof["premises"] as List<*>).map { byNode.getValue(it)["text"] })
                assertEquals(before, Files.size(f.wal))
                f.current = false
                assertFailsWith<IllegalArgumentException> { page(2) }
                f.current = true
                val withdrawn = review.decide(approved.snapshotCid, approved.revision, "correct")
                val next = (withdrawn["revision"] as Number).toLong()
                assertFailsWith<IllegalArgumentException> {
                    ReviewedTaxonomyPlan.report(review, f.cas, approved.snapshotCid, next, "instance", "Tenant", "Agent")
                }
            }
        }
    }
}
