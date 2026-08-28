package borg.trikeshed.forge.server

import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.hook.CausalHookDeliveryLedger
import borg.trikeshed.hook.HookDeliveryLedger
import borg.trikeshed.hook.JvmHookSigner
import borg.trikeshed.lcnc.LcncNode
import borg.trikeshed.lcnc.LcncNodeRunner
import borg.trikeshed.lcnc.LcncProgram
import borg.trikeshed.lcnc.LcncProgramConfix
import borg.trikeshed.lcnc.LcncWire
import borg.trikeshed.lib.toSeries
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R8/J gate: the inbound hook intake actually RUNS the addressed LCNC node — the
 * delivery body arrives as the named port's input, the run lands as a hook-run/
 * receipt, and a duplicate NUID does not double-run. Also gates ledger lane replay
 * across a restart (the WAL half J relied on but never proved).
 */
class WebhookLcncDispatchTest {

    private fun fixtureProgramJson(): String {
        val program = LcncProgram(
            name = "hookprog",
            nodes = listOf(LcncNode(id = "n1", type = "test.echo")).toSeries(),
            wires = emptyList<LcncWire>().toSeries(),
        )
        return LcncProgramConfix.toJson(program)
    }

    @Test
    fun deliveryRunsTheAddressedNodeOnceAndLandsReceipts() = runTest {
        val blackboard = ConfixBlackboard()
        var runs = 0
        val runners = mapOf<String, LcncNodeRunner>(
            "test.echo" to LcncNodeRunner { _, inputs ->
                runs++
                mapOf("out" to "echo:${inputs["in"]}")
            },
        )
        val programJson = fixtureProgramJson()
        val seen = HashSet<String>()
        val wire = WebhookWire(
            InboundHookLookup { program, node, port ->
                if (program == "hookprog" && node == "n1" && port == "in") InboundHook("sekret") else null
            },
            HookDeliveryLedger { seen.add(it) },
            lcncHookIntake(blackboard, runners) { name ->
                if (name == "hookprog") programJson.encodeToByteArray() else null
            },
        )
        val body = "{\"value\":7}"
        val sig = JvmHookSigner.sign("sekret", body)
        fun request(nuid: String) =
            "POST /hook/hookprog/n1/in HTTP/1.1\r\nX-Delivery-NUID: $nuid\r\nX-TrikeShed-Signature: $sig\r\n\r\n$body"

        assertEquals(202, wire.route("POST", "/hook/hookprog/n1/in", request("d-1"))?.status)
        assertEquals(1, runs, "the addressed node ran")
        val runReceipt = blackboard.get("hook-run/hookprog/n1/in/d-1")
        assertTrue(runReceipt is Map<*, *> && runReceipt["status"] == "ran", "run receipt landed: $runReceipt")
        assertTrue(blackboard.get("hook-intake/hookprog/n1/in/d-1") != null, "intake receipt landed")

        assertEquals(200, wire.route("POST", "/hook/hookprog/n1/in", request("d-1"))?.status)
        assertEquals(1, runs, "duplicate NUID does not double-run the node")
    }

    @Test
    fun unresolvableProgramOrRunnerLandsAnHonestReceiptNotACrash() = runTest {
        val blackboard = ConfixBlackboard()
        val wire = WebhookWire(
            InboundHookLookup { _, _, _ -> InboundHook("sekret") },
            HookDeliveryLedger { true },
            lcncHookIntake(blackboard, emptyMap()) { null },
        )
        val body = "{}"
        val sig = JvmHookSigner.sign("sekret", body)
        val req = "POST /hook/ghost/n/in HTTP/1.1\r\nX-Delivery-NUID: g-1\r\nX-TrikeShed-Signature: $sig\r\n\r\n$body"
        assertEquals(202, wire.route("POST", "/hook/ghost/n/in", req)?.status)
        val receipt = blackboard.get("hook-run/ghost/n/in/g-1")
        assertTrue(receipt is Map<*, *> && receipt["status"] == "no-such-program", "honest status: $receipt")
    }

    @Test
    fun ledgerLanesReplayAcrossRestartAndDoNotCollide() = runTest {
        val dir = File.createTempFile("hook-ledger", "").apply { delete(); mkdirs() }
        try {
            val inWal = File(dir, "in.wal")
            val outWal = File(dir, "out.wal")
            val inbound = CausalHookDeliveryLedger.open(inWal)
            val outbound = CausalHookDeliveryLedger.open(outWal, "hook-delivery-out/")
            assertTrue(inbound.acceptOnce("same-nuid"))
            assertTrue(outbound.acceptOnce("same-nuid"), "lanes are separate acceptance spaces")
            assertTrue(!inbound.acceptOnce("same-nuid"))

            // Restart: replay must restore the accepted sets per lane.
            val inbound2 = CausalHookDeliveryLedger.open(inWal)
            val outbound2 = CausalHookDeliveryLedger.open(outWal, "hook-delivery-out/")
            assertTrue(!inbound2.acceptOnce("same-nuid"), "inbound replayed across restart")
            assertTrue(!outbound2.acceptOnce("same-nuid"), "outbound replayed across restart")
            assertTrue(inbound2.acceptOnce("fresh"), "fresh NUIDs still accepted after replay")
        } finally {
            dir.deleteRecursively()
        }
    }
}
