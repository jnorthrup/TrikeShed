package borg.trikeshed.lib

actual fun monotonicNowMillis(): Long = java.lang.System.nanoTime() / 1_000_000
