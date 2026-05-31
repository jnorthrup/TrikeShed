package borg.trikeshed.cursor

/**
 * JVM implementation — required by BlackboardOverlay.
 */
actual fun currentTimeMillis(): Long = java.lang.System.currentTimeMillis()