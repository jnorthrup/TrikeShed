package borg.trikeshed

import kotlinx.coroutines.CoroutineScope

/**
 * The JS half of the commonTest `runBlocking` expect. JavaScript has no blocking wait, so this
 * mirrors the wasmJs actual: it lets the JS test target COMPILE (until 2026-09-06 it could not,
 * 214 errors across 20 commonTest files) and makes any coroutine test that reaches it fail
 * loudly on this target rather than pass vacuously. Pure suites (the document surface's
 * renderer and page model) run here for real.
 */
actual fun <T> runBlocking(block: suspend CoroutineScope.() -> T): T {
    TODO("runBlocking has no JS actual: coroutine commonTests run on the JVM and native targets")
}
