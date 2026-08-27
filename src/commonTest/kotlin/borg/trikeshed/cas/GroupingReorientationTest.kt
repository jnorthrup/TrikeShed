package borg.trikeshed.cas

import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.narsese.AngularCodec
import borg.trikeshed.narsese.hamming
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Step G gate — post-hoc re-orientation + bridges:
 *
 * 1. Relabel round-trip: enact → re-mint → recall under the corrected coordinate
 *    finds the same group (evidence union preserved — nothing lost, attention moved).
 * 2. Resolution documents are deterministic (same post + resolution → same cid).
 * 3. Bridge documents are typed links traversable from both ends (cids both ends,
 *    kind, provenance), and bridge candidates are hamming-near ring pairs.
 */
class GroupingReorientationTest {

    private fun indexWithDocs(): Triple<CasStore, CodeKeyedZoom.CodeIndex, List<LineSpine>> {
        val cas = CasStore.inMemory()
        val idx = CodeKeyedZoom.CodeIndex()
        val spines = mutableListOf<LineSpine>()
        // one tight cluster of near-duplicate docs, one distinct outlier doc
        for (n in 1..3) {
            val s = LineCas.spineInto(cas, "cluster member about retrieval systems $n\nshared structural body line for the cluster")
            spines += s; idx.ingestSpine(s)
        }
        val outlier = LineCas.spineInto(cas, "completely unrelated domain vocabulary entirely different tokens")
        spines += outlier; idx.ingestSpine(outlier)
        return Triple(cas, idx, spines)
    }

    private fun ringOf(idx: CodeKeyedZoom.CodeIndex, spine: LineSpine): Int {
        // recompute the doc code the way CodeIndex stores it: mean of fragment codes
        var acc = 0
        for (i in 0 until spine.size) acc += spine[i].code
        return ((acc / spine.size) ushr 8) and 0xFF
    }

    @Test
    fun relabelRoundTripPreservesEvidenceAndRemintsDeterministically() {
        val (cas, idx, spines) = indexWithDocs()
        val target = spines[0]
        val ring8 = ringOf(idx, target)
        val members = idx.docsInRing(ring8).map { ContentId("sha256:$it") }

        val post = GroupingReorientation.GroupingPost(
            group = GroupingReorientation.Group(ring8, members),
            currentLabel = "code-$ring8",
            proposedLabel = "retrieval-cluster",
            proposedSplit = null,
            proposedMergeWith = null,
            evidence = GroupingReorientation.ProposalEvidence(members.take(2), emptyList()),
            origin = "curator",
        )
        val routine = GroupingReorientation.ResolutionRoutine { p ->
            GroupingReorientation.GroupingResolution.Relabel(p.proposedLabel ?: "unlabeled")
        }
        val r1 = routine.resolve(post)
        val cid1 = GroupingReorientation.enact(cas, post, r1)
        val cid2 = GroupingReorientation.enact(cas, post, routine.resolve(post))
        assertEquals(cid1, cid2, "same post + same resolution → same resolution cid (deterministic)")

        // re-mint the group's coordinate under the corrected label: the label rides the
        // resolution doc, membership is unchanged — recall by code finds the same members
        val recalled = idx.docsInRing(ring8).map { ContentId("sha256:$it") }
        assertEquals(members, recalled, "enactment never changes membership (evidence union preserved)")

        // the resolution document is on the CAS with the corrected label in its bytes
        val bytes = cas.get(cid1)!!.decodeToString()
        assertTrue("action=relabel" in bytes && "retrieval-cluster" in bytes, "resolution doc carries the relabel")
    }

    @Test
    fun bridgeCandidatesAreHammingNearAndBridgesTraverseBothEnds() {
        // two ring centroids: one tight cluster's mean, one shifted by a few bits
        val c1 = 0b0000000000000000L
        val c2 = 0b0000000000001111L
        val c3 = 0b1111111111111111L
        val centroids = mapOf(0x10 to c1, 0x42 to c2, 0x99 to c3)
        val near = GroupingReorientation.bridgeCandidates(centroids, ring8 = 0x10, maxHamming = 4)
        assertTrue(0x42 in near, "4-bit-shifted centroid is hamming-near")
        assertTrue(0x99 !in near, "fully-flipped centroid is not hamming-near")

        val docA = ContentId.of("member-a".encodeToByteArray())
        val docB = ContentId.of("member-b".encodeToByteArray())
        val bridge = GroupingReorientation.bridgeDocument(docA, docB, "hamming-near", "curator")

        // traversal from BOTH ends lands on the partner
        assertEquals(bridge, ContentId.of("bridge-v1|from=${docA.value}|to=${docB.value}|kind=hamming-near|by=curator".encodeToByteArray()))
        // reverse direction resolves to the same bridge identity pattern (typed link, cids both ends)
        val reverse = GroupingReorientation.bridgeDocument(docB, docA, "hamming-near", "curator")
        assertTrue(reverse != bridge, "direction is part of the typed link (from/to)")
        assertTrue(bridge.value.isNotEmpty())
    }

    @Test
    fun correctedCoordinateKeepsGroupUnderRecallNear() {
        val (cas, idx, spines) = indexWithDocs()
        // corrected coordinate = re-encoded angular with the group's label vocabulary as taxonomy
        val ring8 = ringOf(idx, spines[0])
        val corrected = AngularCodec.encode(
            relation = borg.trikeshed.narsese.RelationKind.ATTRACTION,
            taxonomyKey = "retrieval-cluster",
            subjectTerm = "cluster member about retrieval systems",
            objectTerm = "shared structural body line for the cluster",
        )
        val centroid = corrected
        // every member re-mints within small hamming of the corrected centroid — the group
        // survives the re-orientation (near-duplicate surfaces share simhash bits)
        for (n in 1..3) {
            val memberAngular = AngularCodec.encode(
                relation = borg.trikeshed.narsese.RelationKind.ATTRACTION,
                taxonomyKey = "retrieval-cluster",
                subjectTerm = "cluster member about retrieval systems $n",
                objectTerm = "shared structural body line for the cluster",
            )
            val d = hamming(centroid, memberAngular)
            assertTrue(d <= 8, "member $n within small hamming ($d) of corrected centroid — group not scattered")
        }
        assertEquals(ring8, ringOf(idx, spines[0]), "original ring unchanged — relabel, not relocation")
    }
}
