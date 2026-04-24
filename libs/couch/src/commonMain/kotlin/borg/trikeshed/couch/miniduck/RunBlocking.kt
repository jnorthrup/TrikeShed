package borg.trikeshed.couch.miniduck

expect fun <T> runBlockingCommon(block: suspend () -> T): T
