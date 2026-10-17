package borg.trikeshed.userspace.benchmark

/**
 * The commonBench workloads on runtimes JMH cannot drive (a native image): 5 warmup and 10 measured iterations of one
 * second each, as the kotlinx-benchmark configurations run them. Arguments: mode (emulated | native), fixture path.
 */
fun main(args: Array<String>) {
    val mode = args.getOrNull(0) ?: "emulated"
    val path = args.getOrNull(1) ?: "/tmp/trikeshed-uring-bench.bin"
    val nop = UringNopBench().also { it.mode = mode; it.open() }
    iterate("UringNopBench.nop", mode) { nop.nop() }
    nop.close()
    val file = UringFileBench().also { it.mode = mode; it.path = path; it.open() }
    iterate("UringFileBench.write", mode) { file.write() }
    iterate("UringFileBench.read", mode) { file.read() }
    file.close()
}

private var sink = 0

/** Invocations of [op] per second over one second. */
private inline fun second(op: () -> Int): Double {
    val start = System.nanoTime()
    var ops = 0L
    var now: Long
    do {
        sink += op()
        ops++
        now = System.nanoTime()
    } while (now - start < 1_000_000_000L)
    return ops * 1e9 / (now - start)
}

private inline fun iterate(name: String, mode: String, op: () -> Int) {
    repeat(5) { second(op) }
    val scores = DoubleArray(10) { second(op) }
    val mean = scores.average()
    val sd = kotlin.math.sqrt(scores.sumOf { (it - mean) * (it - mean) } / (scores.size - 1))
    println("$name mode=$mode thrpt 10 ${"%.3f".format(mean)} ± ${"%.3f".format(sd)} (sd) ops/s sink=$sink")
}
