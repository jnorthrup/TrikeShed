package borg.trikeshed.lcnc

import borg.trikeshed.lib.toSeries
import borg.trikeshed.runBlocking
import kotlin.test.*

/**
 * The convening semantics (topology ruling + wise-micali step 11): odd
 * membership decides cleanly; even membership's tie is a SIGNAL (further
 * research), never a forced verdict; abstentions never manufacture a tie.
 * Quota-free — no model call anywhere on this path.
 */
class PanelVoteNodeTest {

    private fun ballot(seat: String, approve: Boolean, ok: Boolean = true) =
        mapOf("seat" to seat, "approve" to approve, "ok" to ok, "content" to "$seat says ${if (approve) "aye" else "nay"}")

    private fun vote(ballots: Any?, quorum: String? = null) = runBlocking {
        val node = LcncNode("v", "panel.vote", params = quorum?.let { mapOf("quorum" to it) } ?: emptyMap())
        PanelVoteNode.registry().getValue("panel.vote").run(node, mapOf("ballots" to ballots))
    }

    @Test
    fun oddPanelDecidesCleanly() {
        val out = vote(listOf(ballot("s1", true), ballot("s2", true), ballot("s3", false)))
        assertEquals("ACCEPTED", out["verdict"])
        assertEquals(false, out["tie"])
        assertEquals("advance", out["triage"], "odd membership: a strict majority advances")
    }

    @Test
    fun evenPanelTieIsASignalNotAVerdict() {
        val out = vote(listOf(ballot("s1", true), ballot("s2", false), ballot("s3", true), ballot("s4", false)))
        assertEquals(true, out["tie"], "2–2 is a tie")
        assertEquals("research", out["triage"], "a tie signals further research — never a forced outcome")
        assertEquals("REJECTED", out["verdict"], "the reducer's refusal stands: an unmet quorum reads as refusal downstream")
    }

    @Test
    fun abstentionsNeverManufactureATie() {
        // 3 seats, one transport failure: 2 approve vs 0 reject — no tie, clean accept.
        val out = vote(listOf(ballot("s1", true), ballot("s2", true), ballot("s3", false, ok = false)))
        assertEquals("ACCEPTED", out["verdict"])
        assertEquals(false, out["tie"], "an abstention carries zero weight either way")
        val tally = out["tally"] as Map<*, *>
        assertEquals(1.0, tally["abstain"])
    }

    @Test
    fun declaredQuorumOutranksTheMajorityDefault() {
        // 3 of 4 approve but the workgroup demands unanimity (quorum 4).
        val out = vote(
            listOf(ballot("s1", true), ballot("s2", true), ballot("s3", true), ballot("s4", false)),
            quorum = "4",
        )
        assertEquals("REJECTED", out["verdict"], "declared quorum wins over the majority default")
        assertEquals(false, out["quorumMet"])
    }

    @Test
    fun malformedMembersBooleansAndMissingApprovalCannotChangeTheElectorate() {
        for (malformed in listOf(null, "ballots", mapOf("seat" to "a"), listOf("not a ballot"),
            listOf(mapOf("approve" to true)), listOf(mapOf("seat" to 1, "approve" to true)),
            listOf(mapOf("seat" to " ", "approve" to true)), listOf(mapOf("seat" to "a")),
            listOf(mapOf("seat" to "a", "approve" to "perhaps")),
            listOf(mapOf("seat" to "a", "approve" to true, "ok" to "maybe")))) {
            assertFailsWith<IllegalArgumentException> { vote(malformed) }
        }
        assertFailsWith<IllegalArgumentException> { vote(listOf(ballot("a", true), ballot("a", true))) }
    }

    @Test
    fun declaredMalformedQuorumAndWeightsAreRefusedInsteadOfFallingBack() {
        for (quorum in listOf("invalid", "NaN", "Infinity", "-1")) {
            assertFailsWith<IllegalArgumentException> { vote(listOf(ballot("a", true)), quorum) }
        }
        assertEquals("ACCEPTED", vote(listOf(ballot("a", true)), "  ")["verdict"])
        for (weight in listOf(null, "invalid", "NaN", "Infinity", -1.0, true)) {
            assertFailsWith<IllegalArgumentException> { vote(listOf(ballot("a", true) + ("weight" to weight))) }
        }
        assertFailsWith<IllegalArgumentException> {
            vote(listOf(ballot("a", true) + ("weight" to Double.MAX_VALUE),
                ballot("b", false) + ("weight" to Double.MAX_VALUE)))
        }
    }

    @Test
    fun fractionalWeightsAndStringBooleansPreserveExplicitBallotsAndFailureEvidence() {
        val ballots = arrayOf(
            mapOf("seat" to "a", "role" to "judge", "approve" to "true", "weight" to "0.2", "content" to "aye"),
            mapOf("seat" to "b", "approve" to "false", "weight" to 0.1, "content" to "nay"),
            mapOf("seat" to "c", "ok" to "false", "weight" to 4.0, "error" to "provider timeout"),
        )
        val out = vote(ballots)
        assertEquals("ACCEPTED", out["verdict"])
        assertEquals(true, out["quorumMet"])
        assertEquals(mapOf("approve" to 0.2, "reject" to 0.1, "abstain" to 4.0), out["tally"])
        assertEquals(listOf("nay"), out["dissent"])
        val transcript = out["transcript"] as List<*>
        assertEquals(listOf("a", "b", "c"), transcript.map { (it as Map<*, *>)["seat"] })
        assertEquals("provider timeout", (transcript[2] as Map<*, *>)["error"])
        assertEquals(false, (transcript[2] as Map<*, *>)["ok"])
        assertEquals(out, vote(ballots), "The same explicit ballots produce the same summary evidence")
        val json = borg.trikeshed.parse.jsonOf(out).encodeToByteArray()
        val decoded = borg.trikeshed.parse.reify(json) as Map<*, *>
        assertEquals("provider timeout", (((decoded["transcript"] as List<*>)[2]) as Map<*, *>)["error"])
        assertEquals(true, decoded["quorumMet"])
    }

    @Test
    fun defaultQuorumRemainsStrictForWeightsSmallerThanTheFormerEpsilon() {
        val out = vote(listOf(ballot("a", true) + ("weight" to Double.MIN_VALUE)))
        assertEquals("ACCEPTED", out["verdict"])
        assertEquals(true, out["quorumMet"])
        val tie = vote(listOf(ballot("a", true) + ("weight" to 0.1), ballot("b", false) + ("weight" to 0.1)))
        assertEquals(true, tie["tie"])
        assertEquals(false, tie["quorumMet"])
        assertEquals("research", tie["triage"])
    }

    // ── the concentric use: a workgroup RING votes on ballots conferred from
    //    the enclosing ring — the legal-team shape, machine-native ──────────

    @Test
    fun workgroupRingVotesOnBallotsFromTheEnclosingRing() = runBlocking {
        val program = LcncProgram(
            "workgroup",
            listOf(
                LcncNode("collect", "ballots.fixture"),
                LcncNode("wg", LcncContracts.SCOPE, children = listOf(
                    LcncNode("v", "panel.vote"),
                    LcncNode("o", LcncContracts.SCOPE_OUT, params = mapOf("name" to "triage")),
                ).toSeries()),
            ).toSeries(),
            listOf(
                LcncWire("collect", "ballots", "v", "ballots"), // enclosing output → inner consumer
                LcncWire("v", "triage", "o", "value"),
            ).toSeries(),
        )
        val reg = PanelVoteNode.registry() + mapOf(
            "ballots.fixture" to LcncNodeRunner { _, _ ->
                mapOf("ballots" to listOf(ballot("s1", true), ballot("s2", true), ballot("s3", false)))
            },
        )
        val res = LcncRunner(reg).runProcedure(program)
        assertEquals("advance", (res.nodeOutputs["wg"] as Map<*, *>)["triage"],
            "the workgroup ring consumed the enclosing ring's ballots and yielded only its triage")
    }
}
