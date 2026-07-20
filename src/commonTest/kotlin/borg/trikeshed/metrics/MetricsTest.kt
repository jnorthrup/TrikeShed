package borg.trikeshed.metrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class MetricsTest {
    @Test
    fun testCounter() {
        val counter = DefaultCounter("test_counter")
        counter.inc()
        counter.inc(2)
        assertEquals(3L, counter.get())
    }

    @Test
    fun testGauge() {
        val gauge = DefaultGauge("test_gauge")
        gauge.set(10.0)
        gauge.inc(2.5)
        gauge.dec(1.0)
        assertEquals(11.5, gauge.get())
    }

    @Test
    fun testHistogram() {
        val histogram = DefaultHistogram("test_histogram")
        histogram.observe(0.05)
        histogram.observe(0.5)
        histogram.observe(1.0)
        assertEquals(3L, histogram.getCount())
        assertEquals(1.55, histogram.getSum())
        assertEquals(1L, histogram.getBuckets()[0.05])
        assertEquals(2L, histogram.getBuckets()[0.5])
        assertEquals(3L, histogram.getBuckets()[1.0])
    }

    @Test
    fun testTimer() {
        val timer = DefaultTimer("test_timer")
        timer.record(1.seconds)
        assertEquals(1L, timer.getCount())
        assertEquals(1.seconds, timer.getTotalTime())
    }
}
