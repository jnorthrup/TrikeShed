package borg.trikeshed.test

import kotlinx.coroutines.runBlocking

fun <T> runTest(block: suspend () -> T): T = runBlocking { block() }
