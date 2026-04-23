package borg.trikeshed.test

import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest as kotlinxRunTest

fun runTest(block: suspend () -> Unit): TestResult = kotlinxRunTest { block() }
