package borg.trikeshed.export

import borg.trikeshed.metrics.DefaultCounter
import borg.trikeshed.metrics.DefaultGauge
import kotlin.test.Test
import kotlin.test.assertTrue

class ExportAdaptersTest {
    @Test
    fun testPrometheusExporter() {
        val counter = DefaultCounter("http_requests_total", mapOf("method" to "GET"))
        counter.inc(5)
        
        val gauge = DefaultGauge("memory_usage_bytes")
        gauge.set(1024.0)
        
        val exporter = PrometheusExporter()
        val result = exporter.export(listOf(counter, gauge))
        
        assertTrue(result.contains("http_requests_total{method=\"GET\"} 5"))
        assertTrue(result.contains("memory_usage_bytes 1024.0"))
    }
    
    @Test
    fun testJsonExporter() {
        val counter = DefaultCounter("http_requests_total", mapOf("method" to "GET"))
        counter.inc(5)
        
        val exporter = JsonExporter()
        val result = exporter.export(listOf(counter))
        
        assertTrue(result.contains("\"name\": \"http_requests_total\""))
        assertTrue(result.contains("\"type\": \"counter\""))
        assertTrue(result.contains("\"value\": 5"))
    }
}
