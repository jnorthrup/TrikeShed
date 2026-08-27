package borg.trikeshed.forge.server

import borg.trikeshed.hook.HookDeliveryLedger
import borg.trikeshed.hook.JvmHookSigner
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class WebhookWireTest {
    @Test
    fun signaturePrecedesIdempotentEnvelopeIntakeAndUnmatchedIs404() = runTest {
        val seen = HashSet<String>()
        val intake = ArrayList<HookIntake>()
        val wire = WebhookWire(
            InboundHookLookup { program, node, port ->
                if (program == "p" && node == "n" && port == "in") InboundHook("sekret") else null
            },
            HookDeliveryLedger { seen.add(it) },
            HookEnvelopeIntake { intake.add(it) },
        )
        val body = "{\"value\":1}"
        val sig = JvmHookSigner.sign("sekret", body)
        fun request(signature: String, nuid: String) =
            "POST /hook/p/n/in HTTP/1.1\r\nX-Delivery-NUID: $nuid\r\nX-TrikeShed-Signature: $signature\r\n\r\n$body"

        assertEquals(202, wire.route("POST", "/hook/p/n/in", request(sig, "d-1"))?.status)
        assertEquals(1, intake.size)
        assertEquals("d-1", intake[0].nuid)
        assertEquals(body, intake[0].body)

        assertEquals(200, wire.route("POST", "/hook/p/n/in", request(sig, "d-1"))?.status)
        assertEquals(1, intake.size, "duplicate delivery does not double-run node")

        assertEquals(401, wire.route("POST", "/hook/p/n/in", request("00", "d-2"))?.status)
        assertEquals(1, intake.size, "bad signature rejected before intake")
        assertEquals(404, wire.route("POST", "/hook/p/missing/in", request(sig, "d-3"))?.status)
    }
}
