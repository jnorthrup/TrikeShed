package borg.trikeshed.userspace.benchmark

import kotlinx.coroutines.runBlocking

object JvmUringBenchmark {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking { uringBenchmarkMain(args) }
}
