package borg.trikeshed.brc

import java.io.File
import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import org.openjdk.jmh.runner.Runner
import org.openjdk.jmh.runner.options.OptionsBuilder
import org.openjdk.jmh.runner.options.TimeValue

/**
 * JMH Benchmarks for all BRC (Borg Research Challenge) variants.
 *
 * Benchmarks 4 available JVM variants with proper warmup/measurement configuration.
 * Warmup: 3 x 10s iterations
 * Measurement: 5 x 10s iterations
 */
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 3, time = 10, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 10, timeUnit = TimeUnit.SECONDS)
open class BrcBenchmark {
    @Param("/tmp/measurements.txt")
    lateinit var testFilePath: String

    @Setup(Level.Trial)
    fun setup() {
        if (!File(testFilePath).exists()) {
            error("Test file not found: $testFilePath. Run ./scripts/create_measurements.sh to create it.")
        }
        println("Using test file: $testFilePath (${File(testFilePath).length()} bytes)")
    }

    // ─────────────────────────────────────────────────────────────
    // Benchmark JVM Variants (available in jvmMain)
    // ─────────────────────────────────────────────────────────────

    @Benchmark
    fun benchmarkBrcCsvJvm(blackhole: Blackhole) {
        borg.trikeshed.brc.BrcCsvJvm
            .main(arrayOf(testFilePath))
    }

    @Benchmark
    fun benchmarkBrcMmap(blackhole: Blackhole) {
        borg.trikeshed.lib.brc.BrcMmap
            .main(arrayOf(testFilePath))
    }

    @Benchmark
    fun benchmarkBrcDuckDbJvm(blackhole: Blackhole) {
        borg.trikeshed.brc.BrcDuckDbJvm
            .main(arrayOf(testFilePath))
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            val opt =
                OptionsBuilder()
                    .include(BrcBenchmark::class.java.simpleName)
                    .forks(1)
                    .warmupIterations(3)
                    .warmupTime(TimeValue.seconds(10))
                    .measurementIterations(5)
                    .measurementTime(TimeValue.seconds(10))
                    .threads(Runtime.getRuntime().availableProcessors())
                    .jvmArgs("-Xms4G", "-Xmx4G")
                    .build()

            Runner(opt).run()
        }
    }
}
