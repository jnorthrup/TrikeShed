package borg.trikeshed.metrics

import kotlin.time.Duration
import kotlin.time.TimeSource

interface Metric {
    val name: String
    val tags: Map<String, String>
}

data class MetricFamily<T : Metric>(
    val name: String,
    val description: String,
    val type: String,
    val metrics: List<T>
)

interface Counter : Metric {
    fun inc(amount: Long = 1L)
    fun get(): Long
}

interface Gauge : Metric {
    fun set(value: Double)
    fun inc(amount: Double = 1.0)
    fun dec(amount: Double = 1.0)
    fun get(): Double
}

interface Histogram : Metric {
    fun observe(value: Double)
    fun getCount(): Long
    fun getSum(): Double
    fun getBuckets(): Map<Double, Long>
}

interface Timer : Metric {
    fun record(duration: Duration)
    fun <T> time(block: () -> T): T
    fun getCount(): Long
    fun getTotalTime(): Duration
}

class DefaultCounter(override val name: String, override val tags: Map<String, String> = emptyMap()) : Counter {
    private var value = 0L

    override fun inc(amount: Long) {
        require(amount >= 0) { "Counter can only increase" }
        value += amount
    }

    override fun get(): Long = value
}

class DefaultGauge(override val name: String, override val tags: Map<String, String> = emptyMap()) : Gauge {
    private var value = 0.0

    override fun set(value: Double) {
        this.value = value
    }

    override fun inc(amount: Double) {
        this.value += amount
    }

    override fun dec(amount: Double) {
        this.value -= amount
    }

    override fun get(): Double = value
}

class DefaultHistogram(
    override val name: String,
    override val tags: Map<String, String> = emptyMap(),
    val buckets: List<Double> = listOf(0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0)
) : Histogram {
    private var count = 0L
    private var sum = 0.0
    private val bucketCounts = buckets.sorted().associateWith { 0L }.toMutableMap()

    override fun observe(value: Double) {
        count++
        sum += value

        for (bucket in bucketCounts.keys) {
            if (value <= bucket) {
                bucketCounts[bucket] = bucketCounts[bucket]!! + 1L
            }
        }
    }

    override fun getCount(): Long = count
    override fun getSum(): Double = sum
    override fun getBuckets(): Map<Double, Long> = bucketCounts.toMap()
}

class DefaultTimer(override val name: String, override val tags: Map<String, String> = emptyMap()) : Timer {
    private val histogram = DefaultHistogram(name, tags)

    override fun record(duration: Duration) {
        histogram.observe(duration.inWholeNanoseconds.toDouble() / 1_000_000_000.0)
    }

    override fun <T> time(block: () -> T): T {
        val mark = TimeSource.Monotonic.markNow()
        try {
            return block()
        } finally {
            record(mark.elapsedNow())
        }
    }

    override fun getCount(): Long = histogram.getCount()
    override fun getTotalTime(): Duration {
        val totalSeconds = histogram.getSum()
        return kotlin.time.Duration.parse("${totalSeconds}s")
    }
}
