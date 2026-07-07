package borg.trikeshed.confix

import kotlin.test.*
import kotlin.time.measureTime

class ConfixOracleServiceBenchmarkTest {
    @Test fun benchmarkGetTypedefChain() {
        val service = ConfixOracleService()
        val sourceText = buildString { for (i in 0 until 1000) appendLine("typedef Type${i+1} as Type${i};") }
        service.addSource(sourceText, "benchmark.x")
        for (i in 0 until 10) service.getTypedefChain("benchmark.x", "Type0")
        val duration = measureTime { for (i in 0 until 1000) service.getTypedefChain("benchmark.x", "Type0") }
        println("BENCHMARK_RESULT: ${duration.inWholeMilliseconds} ms")
    }
}
