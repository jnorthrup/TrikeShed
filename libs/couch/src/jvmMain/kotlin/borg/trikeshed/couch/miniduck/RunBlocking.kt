package borg.trikeshed.couch.miniduck

actual fun <T> runBlockingCommon(block: suspend () -> T): T = kotlinx.coroutines.runBlocking { block() }
