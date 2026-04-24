package borg.trikeshed.couch.miniduck

// JS actual implementation: runBlocking is not supported on JS in this project; provide a compile-time stub.
actual fun <T> runBlockingCommon(block: suspend () -> T): T =
    throw UnsupportedOperationException("runBlockingCommon is not supported on JS; call suspend APIs from coroutines instead")
