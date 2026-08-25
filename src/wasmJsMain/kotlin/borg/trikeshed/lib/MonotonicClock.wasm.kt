package borg.trikeshed.lib

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("() => Date.now()")
private external fun jsDateNow(): Double

actual fun monotonicNowMillis(): Long = jsDateNow().toLong()
