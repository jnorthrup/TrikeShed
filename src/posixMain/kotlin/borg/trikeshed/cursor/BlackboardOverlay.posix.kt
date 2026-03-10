package borg.trikeshed.cursor

import kotlinx.datetime.Clock

/**
 * POSIX implementation of currentTimeMillis.
 */
actual fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()
