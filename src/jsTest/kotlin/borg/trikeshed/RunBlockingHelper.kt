package borg.trikeshed

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise

actual fun runBlocking(block: suspend () -> Unit): dynamic {
    return GlobalScope.promise { block() }
}
