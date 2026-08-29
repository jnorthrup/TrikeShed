package borg.trikeshed.narsese

import borg.trikeshed.cursor.BudgetCoord
import borg.trikeshed.job.ContentId
import borg.trikeshed.lcnc.LcncNode
import borg.trikeshed.lib.size
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** BeliefsNodes gate: the BeliefWire behaviors as runners return sane shapes off a real bag. */
class BeliefsNodesTest {

    private fun node(type: String, params: Map<String, String> = emptyMap()) =
        LcncNode(id = "n", type = type, params = params)

    private fun angularOf(subject: String) = AngularCodec.encode(
        relation = RelationKind.CAUSALITY,
        taxonomyKey = "review",
        subjectTerm = subject,
    )

    private fun signalFor(subject: String, positive: Boolean = true) = SemanticSignal(
        angular = angularOf(subject),
        evidence = if (positive) EvidenceCoord(2 * Nal.UNIT, 0L) else EvidenceCoord(0L, 2 * Nal.UNIT),
        relation = RelationKind.CAUSALITY,
        subjectCid = ContentId.of(subject.encodeToByteArray()).value,
    )

    /** single-consumer intake: poll until the condition holds (Default-dispatcher consumer). */
    private suspend fun await(message: String, cond: () -> Boolean) {
        withTimeout(60_000) { while (!cond()) delay(10) }
        assertTrue(cond(), message)
    }

    private suspend fun mintedBag(vararg subjects: Pair<String, Boolean>): BeliefBagElement {
        val bag = BeliefBagElement(capacity = 64)
        bag.open()
        val distinct = subjects.map { (s, _) -> angularOf(s) }.toSet().size
        for ((subject, positive) in subjects) {
            bag.intake.send(BeliefIntake.Mint(signalFor(subject, positive), BudgetCoord(0.8f, 0.5f, 0.6f)))
        }
        await("minted beliefs must land") { bag.size >= distinct }
        return bag
    }

    @Test
    fun introspectReturnsFieldShape() = runTest {
        val bag = mintedBag(
            "smoke detector" to true, "rain dance" to true,
            "coffee brewing" to true, "harbor tide" to false,
        )
        val out = BeliefsNodes.introspectRunner(bag)
            .run(node("beliefs.introspect", mapOf("concepts" to "2", "cruxTop" to "4")), emptyMap())
        @Suppress("UNCHECKED_CAST")
        val field = out["field"] as Map<String, Any?>
        assertEquals(bag.size, field["size"])
        val cruxBits = field["cruxBits"] as List<*>
        assertEquals(4, cruxBits.size)
        for (b in cruxBits) {
            val m = b as Map<*, *>
            assertTrue(m["bit"] is Int && m["loading"] is Float, "crux bit rows carry {bit, loading}")
        }
        val concepts = field["concepts"] as List<*>
        assertEquals(2, concepts.size)
        for (c in concepts) {
            val m = c as Map<*, *>
            assertNotNull(m["variance"])
            assertEquals(4, (m["topBits"] as List<*>).size)
        }
        assertTrue(field["cohortT2"] is Float, "pen-cohort Hotelling T² rides along")
        bag.drain()
    }

    @Test
    fun resonateRawAndWhitenedSplitFronts() = runTest {
        val bag = mintedBag(
            "smoke detector" to true, "rain dance" to true, "harbor tide" to false,
        )
        val runner = BeliefsNodes.resonateRunner(bag, glossOf = { "g:$it" })

        val raw = runner.run(
            node("beliefs.resonate", mapOf("mode" to "raw", "k" to "4", "taxonomy" to "review")),
            mapOf("goal" to "smoke detector"),
        )
        val syn = raw["synonymPeaks"] as List<*>
        val ant = raw["antonymPeaks"] as List<*>
        assertTrue(syn.isNotEmpty(), "positive-polarity beliefs form the support front")
        assertTrue(ant.isNotEmpty(), "negative-polarity beliefs form the refutation front")
        val row = syn.first() as Map<*, *>
        for (key in listOf("angular", "gloss", "expectation", "frequency", "pri", "level", "relation")) {
            assertTrue(key in row, "raw peak rows carry $key")
        }
        assertTrue((row["gloss"] as String).startsWith("g:"), "gloss seam is consulted")
        assertEquals(raw["synonyms"], syn, "contract-name alias mirrors synonymPeaks")

        val whitened = runner.run(
            node("beliefs.resonate", mapOf("mode" to "whitened", "k" to "4")),
            mapOf("goal?" to "smoke detector"),
        )
        assertEquals("whitened", whitened["mode"])
        val wsyn = whitened["synonymPeaks"] as List<*>
        assertTrue(wsyn.isNotEmpty(), "whitened support front over the moment field")
        val wrow = wsyn.first() as Map<*, *>
        for (key in listOf("angular", "mahalanobis", "activation", "pri", "frequency", "level")) {
            assertTrue(key in wrow, "whitened peak rows carry $key")
        }

        val unfed = runner.run(node("beliefs.resonate"), emptyMap())
        assertEquals("goal required", unfed["error"], "no goal anywhere degrades loudly in-band")
        bag.drain()
    }

    @Test
    fun reviewLandsObservationsAndInductions() = runTest {
        val bag = BeliefBagElement(capacity = 64)
        bag.open()
        val review = TurnReviewElement(bag)
        review.open()
        val glossed = mutableListOf<Pair<Long, String>>()
        val runner = BeliefsNodes.reviewRunner(review, glossSink = { a, g -> glossed.add(a to g) })

        val facts = listOf(
            mapOf("verb" to "bash", "ok" to true, "context" to "build", "object" to "gradle"),
            mapOf("verb" to "edit", "ok" to true, "context" to "build", "object" to "kotlin"),
        )
        val out = runner.run(node("beliefs.review"), mapOf("facts" to facts))
        assertEquals(2, out["factsParsed"])
        val landed = out["landed"] as List<*>
        val glosses = out["glosses"] as List<*>
        assertEquals(3, landed.size, "2 observations + 1 same-context induction")
        assertEquals(landed.size, glosses.size)
        assertEquals(landed.size, glossed.size, "every landed pair reaches the gloss sink")
        val first = landed.first() as Map<*, *>
        assertNotNull(first["angular"]); assertNotNull(first["gloss"])
        await("landed beliefs must reach the bag") { bag.size >= 3 }

        // Raw JSON string facts parse identically (same angulars revise, never duplicate).
        val jsonOut = runner.run(
            node("beliefs.review"),
            mapOf("facts" to """[{"verb":"bash","ok":true,"context":"build","object":"gradle"}]"""),
        )
        assertEquals(1, ((jsonOut["landed"]) as List<*>).size)

        // A non-list payload (the preset wires introspect's field json here) degrades to zero facts.
        val degraded = runner.run(node("beliefs.review"), mapOf("facts" to mapOf("size" to 3)))
        assertEquals(0, degraded["factsParsed"])
        assertEquals(0, (degraded["landed"] as List<*>).size)
        bag.drain()
    }

    @Test
    fun encodeMintsTheCentroidRecallNearDemands() = runTest {
        val bag = mintedBag("smoke detector" to true)
        val out = BeliefsNodes.encodeRunner().run(
            node("nal.encode", mapOf("relation" to "causality", "taxonomy" to "review")),
            mapOf("subject" to "smoke detector"),
        )
        val centroid = (out["centroid"] as String).toLong()
        assertEquals(angularOf("smoke detector"), centroid, "nal.encode mirrors AngularCodec.encode")
        assertEquals(1, bag.recallNear(centroid, 0).size, "the minted centroid addresses recallNear exactly")

        val graded = BeliefsNodes.encodeRunner().run(
            node("nal.encode", mapOf(
                "relation" to "ATTRACTION", "subject" to "smoke detector",
                "object" to "alarm", "grade" to "year",
            )),
            emptyMap(),
        )
        val g = (graded["centroid"] as String).toLong()
        assertEquals(RelationKind.ATTRACTION, AngularCodec.Fields.relationOf(g))
        assertEquals(TemporalGrade.YEAR, AngularCodec.Fields.gradeOf(g))

        val unfed = BeliefsNodes.encodeRunner().run(node("nal.encode"), emptyMap())
        assertEquals("subject required", unfed["error"])
        bag.drain()
    }

    @Test
    fun attendRekeysBudgetEvidenceUntouched() = runTest {
        val bag = mintedBag("smoke detector" to true)
        val angular = angularOf("smoke detector")
        val before = bag.snapshot().values.single().evidence
        val out = BeliefsNodes.attendRunner(bag).run(
            node("nal.attend", mapOf("p" to "0.95", "d" to "0.2", "q" to "0.3")),
            mapOf("angular" to angular.toString()),
        )
        val attended = out["attended"] as Map<*, *>
        assertEquals(true, attended["resident"])
        assertEquals(angular.toString(), attended["angular"])
        await("attend must rekey the budget") { (bag.budgetOf(angular)?.pf ?: 0f) > 0.9f }
        assertEquals(before, bag.snapshot().values.single().evidence, "evidence untouched by attend")

        val missing = BeliefsNodes.attendRunner(bag).run(node("nal.attend"), emptyMap())
        assertEquals("angular required", (missing["attended"] as Map<*, *>)["error"])
        bag.drain()
    }

    @Test
    fun reinforceAddsEvidenceDelta() = runTest {
        val bag = mintedBag("smoke detector" to true)
        val angular = angularOf("smoke detector")
        val before = bag.snapshot().values.single().evidence
        val out = BeliefsNodes.reinforceRunner(bag).run(
            node("nal.reinforce", mapOf("wPlus" to "2", "wMinus" to "1", "angular" to angular.toString())),
            emptyMap(),
        )
        val revised = out["revised"] as Map<*, *>
        assertEquals(2 * Nal.UNIT, revised["wPlus"])
        assertEquals(1 * Nal.UNIT, revised["wMinus"])
        await("reinforce must revise evidence") {
            bag.snapshot().values.singleOrNull()?.evidence?.positive == before.positive + 2 * Nal.UNIT
        }
        assertEquals(before.negative + Nal.UNIT, bag.snapshot().values.single().evidence.negative)
        bag.drain()
    }
}
