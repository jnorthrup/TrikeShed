package borg.trikeshed.hook

import borg.trikeshed.couch.CouchStore
import borg.trikeshed.couch.Document
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.j
import borg.trikeshed.parse.json.JsonSupport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Install the production `_changes` → signed HTX webhook subscriber. */
fun installOutboundWebhookBridge(
    couch: CouchStore,
    blackboard: ConfixBlackboard,
    scope: CoroutineScope,
    ledger: HookDeliveryLedger,
): () -> Unit = couch.subscribeMutations { event ->
    val doc: Document? = when (event) {
        is CouchStore.MutationEvent.Inserted -> event.doc
        is CouchStore.MutationEvent.Updated -> event.doc
        is CouchStore.MutationEvent.Deleted -> null
    }
    val docId = when (event) {
        is CouchStore.MutationEvent.Inserted -> event.doc.id
        is CouchStore.MutationEvent.Updated -> event.doc.id
        is CouchStore.MutationEvent.Deleted -> event.docId
    }
    val mutation = when (event) {
        is CouchStore.MutationEvent.Inserted -> "inserted"
        is CouchStore.MutationEvent.Updated -> "updated"
        is CouchStore.MutationEvent.Deleted -> "deleted"
    }
    val eventKind = doc?.fields?.firstOrNull { it.name == "eventKind" || it.name == "kind" }?.value?.toString() ?: mutation
    // The rev is part of the delivery identity: without it, two successive updates of the same
    // doc to the same kind hash to the same NUID and the ledger suppresses the second forever.
    val rev = runCatching { couch.head.getRev(docId) }.getOrNull() ?: ""
    val body = JsonSupport.stringify(mapOf("kind" to eventKind, "mutation" to mutation, "id" to docId, "rev" to rev))
    val eventCid = ContentId.of(body.encodeToByteArray()).hex

    // Read subscription docs on every change: edits become effective without a mutable cache.
    for (subDoc in couch.all()) {
        if (!subDoc.id.startsWith("hooks/")) continue
        val sub = subscriptionOf(subDoc) ?: continue
        val delivery = HookDelivery("${sub.name}:$eventCid", eventKind, body)
        scope.launch {
            val bridge = ChangesWebhookBridge(
                ledger = ledger,
                signer = JvmHookSigner,
                sender = HtxHookSender,
                deadLetters = HookDeadLetterSink { dead ->
                    blackboard.put(
                        "${dead.target}/${dead.subscription}/${dead.delivery.nuid}",
                        mapOf(
                            "subscription" to dead.subscription,
                            "deliveryNuid" to dead.delivery.nuid,
                            "attempts" to dead.attempts.toString(),
                            "error" to dead.error,
                            "body" to dead.delivery.body,
                        ),
                        "webhook",
                    )
                },
                delayFor = { millis -> delay(millis) },
            )
            val receipt = bridge.deliver(sub, delivery)
            blackboard.put(
                "hook-receipt/${sub.name}/${delivery.nuid}",
                mapOf("status" to receipt.status, "attempts" to receipt.attempts.toString(), "target" to receipt.target),
                "webhook",
            )
        }
    }
}

private fun subscriptionOf(doc: Document): HookSubscription? {
    fun field(name: String): String? = doc.fields.firstOrNull { it.name == name }?.value?.toString()
    val target = field("targetUrl") ?: return null
    val secret = field("hmacSecret") ?: return null
    val filterParts = (field("eventFilter") ?: return null).split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val filters = filterParts.size j { i: Int -> filterParts[i] }
    return HookSubscription(
        name = doc.id.removePrefix("hooks/"),
        targetUrl = target,
        eventFilter = filters,
        hmacSecret = secret,
        maxAttempts = field("maxAttempts")?.toIntOrNull() ?: 3,
        backoffMillis = field("backoffMillis")?.toLongOrNull() ?: 250,
        deadLetterTarget = field("deadLetterTarget") ?: "dead-letter/hooks",
    )
}
