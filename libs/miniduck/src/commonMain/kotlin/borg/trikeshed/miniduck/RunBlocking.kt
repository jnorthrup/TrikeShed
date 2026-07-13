package borg.trikeshed.miniduck

/**
 * Platform-agnostic runBlocking.
 * JVM: delegates to kotlinx.coroutines.runBlocking.
 * Non-JVM: throws — native code should use coroutines directly.
 */
expect fun <T> runBlockingCommon(block: suspend () -> T): T
