package borg.trikeshed.cursor

/**
 * JVM implementation of currentTimeMillis.
 */
actual fun currentTimeMillis(): Long = Clocks.System.now()
