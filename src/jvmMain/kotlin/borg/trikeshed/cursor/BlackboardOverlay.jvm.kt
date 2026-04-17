package borg.trikeshed.cursor

/**
 * JVM implementation of currentTimeMillis.
 */
actual fun currentTimeMillis(): Long = java.lang.System.currentTimeMillis()
