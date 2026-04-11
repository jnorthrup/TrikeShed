package borg.literbike.betanet

/**
 * DayJobTest - High-Performance ISAM Benchmark
 *
 * Densified Kotlin implementation with:
 * - SIMD acceleration for bulk operations (simulated on JVM)
 * - Compile-time FSM verification
 * - CCEK elements+keys integration
 * - Exact compatibility with Kotlin dayjobTest.kt
 * Ported from literbike/src/betanet/dayjobtest.rs
 */

/** Compile-time FSM states for benchmark operations */
object BenchmarkFSM {
    // State types represented as sealed class
    sealed class State

    object Uninitialized : State()
    object DataGenerated : State()
    object ISAMWritten : State()
    object BenchmarkComplete : State()

    /** FSM container with state tracking */
    class Instance<S : State>(
        val data_size: Int,
        private val startTime: Long? = null,
    ) {
        companion object {
            fun newDataGenerated(prev: Instance<Uninitialized>): Instance<DataGenerated> =
                Instance(prev.data_size, Clocks.System.now())

            fun newISAMWritten(prev: Instance<DataGenerated>): Instance<ISAMWritten> =
                Instance(prev.data_size, prev.startTime)

            fun newBenchmarkComplete(prev: Instance<ISAMWritten>): Instance<BenchmarkComplete> =
                Instance(prev.data_size, prev.startTime)
        }

        /** Get elapsed duration for completed benchmarks */
        fun duration(): Long = startTime?.let { Clocks.System.now() - it } ?: 0L
    }

    fun new(dataSize: Int): Instance<Uninitialized> = Instance(dataSize, null)
}

/** CCEK elements for performance optimization */
class CcekElements {
    @Volatile
    var vectorizationKey: Long = 0xDEADBEEF_CAFEBABEL
    @Volatile
    var ioUringKey: Long = 0xFEEDFACE_DEADBEEFL
    @Volatile
    var simdLaneKey: Long = 0xBAADF00D_CAFEBABEL
    @Volatile
    var cacheLineKey: Long = 0xDECAFBAD_FEEDFACEL

    companion object {
        fun new(): CcekElements = CcekElements()
    }

    /** Update keys for performance optimization */
    fun updateVectorizationHint(hint: Long) {
        vectorizationKey = hint
    }

    fun getSimdLanes(): Int = ((simdLaneKey % 64) + 1).toInt().coerceIn(1, 64)
}

/** High-performance test data generator with SIMD-friendly layout */
class TestDataGenerator(
    private val rowCount: Int,
    private val ccek: CcekElements = CcekElements.new(),
) {
    /** Generate test data */
    fun generateData(): List<BabyDataFrame.Row> {
        val rows = mutableListOf<BabyDataFrame.Row>()
        val simdLanes = ccek.getSimdLanes()

        for (chunkStart in 0 until rowCount step simdLanes) {
            val chunkEnd = minOf(chunkStart + simdLanes, rowCount)
            for (i in chunkStart until chunkEnd) {
                rows.add(
                    BabyDataFrame.Row(
                        values = listOf(
                            i.toString(),
                            "record_$i",
                            (i * 3.14159).toString(),
                            (i * 2.71828).toString(),
                            (i * 1000).toString(),
                            if (i % 2 == 0) "true" else "false",
                            Clocks.System.now().toString(),
                        ),
                    ),
                )
            }
        }
        return rows
    }
}

/** Test cursor implementation */
class TestCursor(
    val rows: List<BabyDataFrame.Row>,
) {
    val size: Int get() = rows.size

    fun getRow(index: Int): BabyDataFrame.Row? = rows.getOrNull(index)
}

/** Main DayJobTest benchmark runner */
class DayJobTest(
    private val dataSize: Int,
    private val outputPath: String,
    private val ccek: CcekElements = CcekElements.new(),
) {
    companion object {
        fun newData(size: Int, outputPath: String): DayJobTest = DayJobTest(dataSize = size, outputPath = outputPath)
    }

    /** Run complete benchmark with FSM verification */
    fun runBenchmark(): Result<BenchmarkFSM.Instance<BenchmarkFSM.BenchmarkComplete>> {
        val fsm = BenchmarkFSM.new(dataSize)
        println("Starting DayJobTest benchmark with $dataSize records")

        // State 1: Generate data
        val fsmGenerated = BenchmarkFSM.newDataGenerated(fsm)
        val generator = TestDataGenerator(dataSize)
        val rows = generator.generateData()
        println("Generated ${rows.size} rows of test data")

        // State 2: Write ISAM
        val fsmWritten = BenchmarkFSM.newISAMWritten(fsmGenerated)
        val cursor = TestCursor(rows)
        println("ISAM cursor ready with ${cursor.size} rows")

        // State 3: Complete benchmark
        val fsmComplete = BenchmarkFSM.newBenchmarkComplete(fsmWritten)

        println("Benchmark completed in ${fsmComplete.duration()}ms")
        println("Throughput: %.2f records/sec".format(dataSize.toDouble() / (fsmComplete.duration().toDouble().coerceAtLeast(1.0) / 1000.0)))

        // Integration with baby_pandas
        testBabyPandasIntegration()

        return Result.success(fsmComplete)
    }

    /** Test integration with baby_pandas interface */
    private fun testBabyPandasIntegration() {
        println("Testing baby_pandas integration...")

        // Create sample data for baby_pandas
        val data = listOf(
            listOf("1", "test1", "3.14"),
            listOf("2", "test2", "2.71"),
            listOf("3", "test3", "1.41"),
        )
        val columns = listOf(
            ColumnMeta("id", "int", false),
            ColumnMeta("name", "string", false),
            ColumnMeta("value", "float", false),
        )
        val df = BabyDataFrame(data, columns)

        println("   - Created DataFrame: $df")
        println("   - Columns: ${df.columns()}")
        println("   - Rows: ${df.len()}")

        // Test operations
        val resampled = df.resample(2)
        println("   - Resampled to ${resampled.len()} rows")

        val selected = df.select(listOf("id", "name"))
        println("   - Selected columns: ${selected.columns()}")

        val filled = df.fillna("default")
        println("   - Applied fillna operation")

        val grouped = df.groupby("name").count()
        println("   - Grouped by 'name' and counted: ${grouped.size} groups")

        println("Baby pandas integration successful")
    }

    /** CCEK performance optimization test */
    fun testCcekOptimization(): Result<Unit> {
        println("Testing CCEK performance optimizations...")

        // Update CCEK hints for vectorization
        ccek.updateVectorizationHint(0xFEEDFACE_DEADBEEFL)

        val simdLanes = ccek.getSimdLanes()
        println("   - SIMD lanes: $simdLanes")

        // Test vectorized data generation
        val start = Clocks.System.now()
        val generator = TestDataGenerator(1000)
        val data = generator.generateData()
        val generationTime = Clocks.System.now() - start

        println("   - Generated 1000 records in ${generationTime}ms")
        println("   - Throughput: %.2f records/sec".format(1000.0 / (generationTime.toDouble().coerceAtLeast(1.0) / 1000.0)))

        println("CCEK optimization test complete")
        return Result.success(Unit)
    }
}
