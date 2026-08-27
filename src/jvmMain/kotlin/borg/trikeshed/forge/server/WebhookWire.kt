package borg.trikeshed.forge.server

import borg.trikeshed.hook.HookDeliveryLedger
import borg.trikeshed.hook.JvmHookSigner
import borg.trikeshed.litebike.JvmKanbanServer

/** Resolved inbound subscription document for one LCNC input port. */
data class InboundHook(val secret: String)
data class HookIntake(val program: String, val node: String, val port: String, val nuid: String, val body: String)
fun interface InboundHookLookup { fun find(program: String, node: String, port: String): InboundHook? }
fun interface HookEnvelopeIntake { suspend fun accept(envelope: HookIntake) }

/**
 * Step J signed inbound socket: `POST /hook/<program>/<node>/<port>`.
 *
 * Signature verification precedes WAL/idempotency acceptance; unmatched routes are 404; a
 * duplicate delivery NUID is acknowledged but never double-runs the node. The intake callback
 * is the LCNC/WAL envelope seam — this wire owns no program state.
 */
class WebhookWire(
    private val hooks: InboundHookLookup,
    private val ledger: HookDeliveryLedger,
    private val intake: HookEnvelopeIntake,
) {
    companion object {
        val ROUTES: List<Pair<String, String>> = listOf("POST" to "/hook/{program}/{node}/{port}")
    }

    suspend fun route(
        method: String,
        path: String,
        text: String,
        respond: (suspend (ByteArray) -> Unit)? = null,
    ): JvmKanbanServer.HttpResponse? {
        val p = path.substringBefore('?')
        if (!p.startsWith("/hook/")) return null
        if (method != "POST") return JvmKanbanServer.HttpResponse(405, """{"error":"method_not_allowed"}""")
        val parts = p.removePrefix("/hook/").split('/')
        if (parts.size != 3 || parts.any { it.isBlank() }) return JvmKanbanServer.HttpResponse(404, """{"error":"hook_not_found"}""")
        val (program, node, port) = parts
        val hook = hooks.find(program, node, port)
            ?: return JvmKanbanServer.HttpResponse(404, """{"error":"hook_not_found"}""")
        val body = when {
            "\r\n\r\n" in text -> text.substringAfter("\r\n\r\n")
            "\n\n" in text -> text.substringAfter("\n\n")
            else -> text
        }
        fun header(name: String): String? = text.lineSequence()
            .firstOrNull { it.substringBefore(':').trim().equals(name, ignoreCase = true) }
            ?.substringAfter(':')?.trim()
        val signature = header("X-TrikeShed-Signature")
            ?: return JvmKanbanServer.HttpResponse(401, """{"error":"signature_required"}""")
        if (!JvmHookSigner.verifies(hook.secret, body, signature)) {
            return JvmKanbanServer.HttpResponse(401, """{"error":"bad_signature"}""")
        }
        val nuid = header("X-Delivery-NUID") ?: header("Idempotency-Key")
            ?: return JvmKanbanServer.HttpResponse(400, """{"error":"delivery_nuid_required"}""")
        if (!ledger.acceptOnce(nuid)) {
            return JvmKanbanServer.HttpResponse(200, """{"ok":true,"duplicate":true}""")
        }
        intake.accept(HookIntake(program, node, port, nuid, body))
        return JvmKanbanServer.HttpResponse(202, """{"ok":true,"accepted":true}""")
    }
}
