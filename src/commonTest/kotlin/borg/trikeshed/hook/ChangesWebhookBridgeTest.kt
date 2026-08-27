package borg.trikeshed.hook

import borg.trikeshed.lib.s_
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChangesWebhookBridgeTest {
    @Test
    fun signedDeliveryDuplicateSuppressionAndDeadLetterAreObservable() = runTest {
        val seen = HashSet<String>()
        val posts = ArrayList<String>()
        val dead = ArrayList<HookDeadLetter>()
        val ledger = HookDeliveryLedger { seen.add(it) }
        val signer = HookSigner { secret, body -> "$secret:${body.length}" }
        val sender = HookSender { target, body, nuid, sig -> posts.add("$target|$body|$nuid|$sig") }
        val bridge = ChangesWebhookBridge(ledger, signer, sender, HookDeadLetterSink { dead.add(it) }, delayFor = {})
        val sub = HookSubscription("build", "https://receiver.test/hook", s_["commit", "ingest"], "sekret", 2, 1)
        val delivery = HookDelivery("nuid-1", "commit", "{\"sha\":\"abc\"}")

        val receipt = bridge.deliver(sub, delivery)
        assertEquals("delivered", receipt.status)
        assertEquals(1, posts.size)
        assertTrue(posts[0].endsWith("|nuid-1|sekret:13"), "signed POST observed at receiver seam")

        val duplicate = bridge.deliver(sub, delivery)
        assertEquals("duplicate", duplicate.status)
        assertEquals(1, posts.size, "duplicate does not double-run target")

        val failing = ChangesWebhookBridge(
            HookDeliveryLedger { seen.add(it) }, signer,
            HookSender { _, _, _, _ -> error("receiver down") },
            HookDeadLetterSink { dead.add(it) }, delayFor = {},
        )
        val failed = failing.deliver(sub, HookDelivery("nuid-2", "commit", "{}"))
        assertEquals("dead-letter", failed.status)
        assertEquals(2, failed.attempts)
        assertEquals(1, dead.size)
        assertEquals("dead-letter/hooks", dead[0].target)
        assertEquals("nuid-2", dead[0].delivery.nuid)
    }
}
