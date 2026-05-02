package borg.trikeshed.miniduck

/**
 * expect runBlockingCommon: suspending block executor
 */
expect fun <T> runBlockingCommon(block: suspend () -> T): T