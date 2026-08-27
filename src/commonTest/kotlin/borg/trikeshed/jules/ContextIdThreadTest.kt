package borg.trikeshed.jules

import borg.trikeshed.job.ContentId
import borg.trikeshed.modelmux.Frame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Plan step 5 gate: /api/mux/chat threads a conversation identity end to end.
 * The wire accepts `contextId` (a parent cid), sends cid + delta instead of a
 * stateless rebuilt [system, user] pair, returns the child cid, and BrainClient
 * stamps the identity onto receipts via ModelMux.chat's provenance slot.
 *
 * The wire itself is jvmMain (HTTP), so the algebra under test here is the
 * piece commonMain owns: the rolling-cid chain the wire composes. The chain
 * is deterministic — the same (parent, turn) always yields the same child —
 * which is what makes a returned contextId reproducible and reconcilable.
 */
class ContextIdThreadTest {

    @Test
    fun childCidIsDeterministicFromParentAndTurn() {
        val parent = ContentId.of("root-conversation".encodeToByteArray())
        val f1 = Frame.append(Frame(cid = parent, parent = null, turn = ByteArray(0)), "delta-1".encodeToByteArray())
        val f2 = Frame.append(Frame(cid = parent, parent = null, turn = ByteArray(0)), "delta-1".encodeToByteArray())
        assertEquals(f1.cid, f2.cid, "same parent + same turn → same child cid (reconcilable)")
        assertEquals(parent, f1.parent)
    }

    @Test
    fun answerTurnChainsOntoTheReturnedCid() {
        // the wire's contract: request parent P + prompt → answer A, return
        // cid_A = H(P ++ A). The next call sends cid_A and appends its own
        // delta — the chain the commander view and affinity key on.
        val p = ContentId.of("P".encodeToByteArray())
        val answer = Frame.append(Frame(cid = p, parent = null, turn = ByteArray(0)), "A".encodeToByteArray())
        val next = Frame.append(Frame(cid = answer.cid, parent = null, turn = ByteArray(0)), "delta-2".encodeToByteArray())
        assertEquals(answer.cid, next.parent, "next call's parent IS the returned contextId")
        assertTrue(next.cid != answer.cid)
    }

    @Test
    fun divergentAnswersDivergeTheChain() {
        val p = ContentId.of("P".encodeToByteArray())
        val a1 = Frame.append(Frame(cid = p, parent = null, turn = ByteArray(0)), "answer-one".encodeToByteArray())
        val a2 = Frame.append(Frame(cid = p, parent = null, turn = ByteArray(0)), "answer-two".encodeToByteArray())
        assertTrue(a1.cid != a2.cid, "different answers at the same parent diverge — no silent chain merge")
    }

    @Test
    fun malformedContextIdIsRefusedNotGuessed() {
        // the wire refuses a contextId that is not a well-formed ContentId
        // with 400/bad_contextId — mirrors ContentId's own require()
        val bad = "ctx-old-name"
        val parsed = runCatching { ContentId(bad) }.getOrNull()
        assertEquals(null, parsed, "legacy 'ctx-\$name' ids must be rejected, not silently accepted")
    }

}
