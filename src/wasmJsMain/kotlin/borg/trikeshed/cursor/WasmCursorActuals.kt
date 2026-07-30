package borg.trikeshed.cursor

import kotlin.time.Clock

actual fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()

actual fun monotonicNanoTime(): Long = Clock.System.now().toEpochMilliseconds() * 1000000L
actual fun availableProcessors(): Int = 1
