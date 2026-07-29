package borg.trikeshed.lib

import kotlin.js.Date

actual fun monotonicNowMillis(): Long = Date.now().toLong()
