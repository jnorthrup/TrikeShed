package borg.trikeshed.forge.server

import borg.trikeshed.couch.CouchStore
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.hook.CausalHookDeliveryLedger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Inbound wire + shared WAL ledger; outbound `_changes` uses the same acceptance log. */
data class CouchWebhookRuntime(val wire: WebhookWire, val ledger: CausalHookDeliveryLedger)

/** Build the production inbound hook runtime from CAS/Couch subscription docs + CausalWal. */
suspend fun couchWebhookRuntime(
    couch: CouchStore,
    blackboard: ConfixBlackboard,
    stateDir: File,
): CouchWebhookRuntime {
    val ledger = withContext(Dispatchers.IO) {
        CausalHookDeliveryLedger.open(File(stateDir, ".hook-deliveries.wal"))
    }
    val wire = WebhookWire(
        hooks = InboundHookLookup { program, node, port ->
            val doc = couch.all().firstOrNull { d ->
                d.id.startsWith("hooks/") &&
                    d.fields.any { it.name == "program" && it.value?.toString() == program } &&
                    d.fields.any { it.name == "node" && it.value?.toString() == node } &&
                    d.fields.any { it.name == "port" && it.value?.toString() == port }
            } ?: return@InboundHookLookup null
            val secret = doc.fields.firstOrNull { it.name == "hmacSecret" }?.value?.toString()
                ?: return@InboundHookLookup null
            InboundHook(secret)
        },
        ledger = ledger,
        intake = HookEnvelopeIntake { e ->
            blackboard.put(
                "hook-intake/${e.program}/${e.node}/${e.port}/${e.nuid}",
                mapOf(
                    "program" to e.program,
                    "node" to e.node,
                    "port" to e.port,
                    "deliveryNuid" to e.nuid,
                    "body" to e.body,
                ),
                "webhook",
            )
        },
    )
    return CouchWebhookRuntime(wire, ledger)
}
