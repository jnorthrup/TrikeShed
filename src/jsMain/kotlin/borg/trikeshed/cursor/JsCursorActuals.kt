package borg.trikeshed.cursor

actual fun currentTimeMillis(): Long = kotlin.js.Date.now().toLong()
