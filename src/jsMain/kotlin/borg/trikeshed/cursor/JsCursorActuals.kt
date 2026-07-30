package borg.trikeshed.cursor

import kotlin.time.Clock.System

actual fun currentTimeMillis(): Long = kotlin.js.Date.now().toLong()
actual fun monotonicNanoTime(): Long = System.now().toEpochMilliseconds() * 1000000L
actual fun availableProcessors(): Int = 1
