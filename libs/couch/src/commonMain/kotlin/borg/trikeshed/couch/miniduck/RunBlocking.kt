package borg.trikeshed.couch.miniduck

import kotlinx.coroutines.runBlocking

/**
 * Calls `runBlocking` from kotlinx-coroutines-core.
 * On JVM this uses the real coroutine runner.
 * On Native targets this throws — native code should use coroutines directly.
 */
fun <T> runBlockingCommon(block: suspend () -> T): T = runBlocking { block() }
