package borg.trikeshed.cursor

import kotlin.time.Clock.System

/**
 * POSIX implementation of currentTimeMillis.
 */
actual fun currentTimeMillis(): Long = System.now().toEpochMilliseconds()
