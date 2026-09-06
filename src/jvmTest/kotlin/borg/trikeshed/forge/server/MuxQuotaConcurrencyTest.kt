package borg.trikeshed.forge.server

import borg.trikeshed.lib.get
import borg.trikeshed.modelmux.ModelResponseReceipt
import borg.trikeshed.userspace.reactor.MuxReactorElement
import kotlinx.coroutines.*
import modelmux.QuotaLegion
import kotlin.test.Test
import kotlin.test.assertEquals

class MuxQuotaConcurrencyTest {
    @Test fun parallelCompletionsDoNotLoseSpend(): Unit = runBlocking {
        val quota = QuotaLegion(defaultLimit = 1_000_000)
        val reactor = MuxReactorElement()
        reactor.recordAccess("shared", "provider")
        val receipt = ModelResponseReceipt.mint("model", "provider", "request", "chat", 200, 1, 10, 5)
        coroutineScope {
            repeat(8) { launch(Dispatchers.Default) { repeat(2000) { quota.applyReceipt("shared", "provider", receipt, 1000) } } }
        }
        assertEquals(240_000L, quota.standings(reactor.flowState.value, 1000)[0].spent)
    }
}
