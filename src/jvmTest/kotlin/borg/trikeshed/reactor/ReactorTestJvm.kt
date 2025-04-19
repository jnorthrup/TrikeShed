package borg.trikeshed.reactor

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay as coroutinesDelay

actual fun runTest(block: suspend () -> Unit) {
    runBlocking { block() }
}

actual fun createTestReactor(): Reactor = Reactor()

actual fun delay(ms: Long) {
    coroutinesDelay(ms)
}
