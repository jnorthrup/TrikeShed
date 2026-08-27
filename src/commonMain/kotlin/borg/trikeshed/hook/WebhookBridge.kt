package borg.trikeshed.hook

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size

/** Auditable subscription document stored under `hooks/<name>`. */
data class HookSubscription(
    val name: String,
    val targetUrl: String,
    val eventFilter: Series<String>,
    val hmacSecret: String,
    val maxAttempts: Int = 3,
    val backoffMillis: Long = 250,
    val deadLetterTarget: String = "dead-letter/hooks",
)

data class HookDelivery(val nuid: String, val eventKind: String, val body: String)
data class HookReceipt(val nuid: String, val target: String, val attempts: Int, val status: String, val signature: String)
data class HookDeadLetter(val subscription: String, val delivery: HookDelivery, val attempts: Int, val error: String, val target: String)

/** WAL/idempotency seam: true exactly once for a delivery NUID. */
fun interface HookDeliveryLedger { suspend fun acceptOnce(nuid: String): Boolean }

/** HMAC seam; JVM production uses HmacSHA256, common tests can use the same deterministic contract. */
fun interface HookSigner { fun sign(secret: String, body: String): String }

/** Outbound transport seam. Production is HTX/userspace.nio; never a platform HTTP client. */
fun interface HookSender {
    suspend fun post(targetUrl: String, body: String, nuid: String, signature: String)
}

/** Dead-letter lane seam; the daemon lands the value + receipt in its blackboard/CAS lane. */
fun interface HookDeadLetterSink { fun land(dead: HookDeadLetter) }

/**
 * Step J outbound changes→webhook bridge. Filters subscription docs, signs each delivery,
 * retries according to policy, suppresses duplicate NUIDs through the injected WAL ledger,
 * and lands exhausted deliveries in the dead-letter lane with a receipt.
 */
class ChangesWebhookBridge(
    private val ledger: HookDeliveryLedger,
    private val signer: HookSigner,
    private val sender: HookSender,
    private val deadLetters: HookDeadLetterSink,
    private val delayFor: suspend (Long) -> Unit,
) {
    suspend fun deliver(subscription: HookSubscription, delivery: HookDelivery): HookReceipt {
        var matches = false
        for (i in 0 until subscription.eventFilter.size) {
            if (subscription.eventFilter[i] == delivery.eventKind) { matches = true; break }
        }
        if (!matches) {
            return HookReceipt(delivery.nuid, subscription.targetUrl, 0, "filtered", "")
        }
        if (!ledger.acceptOnce(delivery.nuid)) {
            return HookReceipt(delivery.nuid, subscription.targetUrl, 0, "duplicate", "")
        }
        val signature = signer.sign(subscription.hmacSecret, delivery.body)
        var attempt = 0
        var failure = "delivery failed"
        while (attempt < subscription.maxAttempts.coerceAtLeast(1)) {
            attempt++
            try {
                sender.post(subscription.targetUrl, delivery.body, delivery.nuid, signature)
                return HookReceipt(delivery.nuid, subscription.targetUrl, attempt, "delivered", signature)
            } catch (t: Throwable) {
                failure = t.message ?: t::class.simpleName.orEmpty()
                if (attempt < subscription.maxAttempts) delayFor(subscription.backoffMillis * attempt)
            }
        }
        deadLetters.land(HookDeadLetter(subscription.name, delivery, attempt, failure, subscription.deadLetterTarget))
        return HookReceipt(delivery.nuid, subscription.targetUrl, attempt, "dead-letter", signature)
    }
}
