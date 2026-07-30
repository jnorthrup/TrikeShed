package borg.trikeshed.cursor

/**
 * JVM implementation — required by BlackboardOverlay.
 */
actual fun currentTimeMillis(): Long = java.lang.System.currentTimeMillis()

actual fun monotonicNanoTime(): Long = java.lang.System.nanoTime()

actual fun availableProcessors(): Int = Runtime.getRuntime().availableProcessors()