package borg.trikeshed

import kotlinx.coroutines.CoroutineScope

actual fun <T> runBlocking(block: suspend CoroutineScope.() -> T): T {
    TODO("nodejs platform mismatch")
}
