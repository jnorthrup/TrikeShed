package borg.trikeshed.lib

actual fun monotonicNowMillis(): Long = js("Date.now()").unsafeCast<Number>().toLong()
