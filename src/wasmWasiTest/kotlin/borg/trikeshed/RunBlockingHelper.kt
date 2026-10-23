package borg.trikeshed

import kotlinx.coroutines.CoroutineScope

/** WASI has no blocking event loop and kotlinx.coroutines ships no runBlocking for it: coroutine commonTests run on the JVM and native targets. */
actual fun <T> runBlocking(block: suspend CoroutineScope.() -> T): T {
    TODO("runBlocking has no WASI actual: coroutine commonTests run on the JVM and native targets")
}
