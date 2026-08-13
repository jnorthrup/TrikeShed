package borg.trikeshed

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise

actual fun runBlockingTest(block: suspend () -> Unit): dynamic {
    return GlobalScope.promise { block() }
}
