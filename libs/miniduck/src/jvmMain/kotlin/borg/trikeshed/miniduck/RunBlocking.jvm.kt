package borg.trikeshed.miniduck

import kotlinx.coroutines.runBlocking

actual fun <T> runBlockingCommon(block: suspend () -> T): T = runBlocking { block() }
