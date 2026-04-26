package borg.trikeshed.couch.miniduck

actual fun <T> runBlockingCommon(block: suspend () -> T): T =
    throw UnsupportedOperationException("runBlocking not supported on JS")
