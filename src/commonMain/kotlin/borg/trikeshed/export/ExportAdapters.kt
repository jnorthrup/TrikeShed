package borg.trikeshed.export

import borg.trikeshed.metrics.Counter
import borg.trikeshed.metrics.Gauge
import borg.trikeshed.metrics.Histogram
import borg.trikeshed.metrics.Metric
import borg.trikeshed.metrics.Timer

interface MetricsExporter {
    fun export(metrics: List<Metric>): String
}

class PrometheusExporter : MetricsExporter {
    override fun export(metrics: List<Metric>): String {
        val builder = StringBuilder()
        
        for (metric in metrics) {
            val tagsString = if (metric.tags.isNotEmpty()) {
                "{" + metric.tags.entries.joinToString(",") { "${it.key}=\"${it.value}\"" } + "}"
            } else {
                ""
            }
            
            if (metric is Counter) {
                builder.append("# TYPE ${metric.name} counter\n")
                builder.append("${metric.name}$tagsString ${metric.get()}\n")
            } else if (metric is Gauge) {
                builder.append("# TYPE ${metric.name} gauge\n")
                builder.append("${metric.name}$tagsString ${metric.get()}\n")
            } else if (metric is Histogram) {
                builder.append("# TYPE ${metric.name} histogram\n")
                for (entry in metric.getBuckets().entries) {
                    val bucketTags = if (metric.tags.isNotEmpty()) {
                        "{" + metric.tags.entries.joinToString(",") { "${it.key}=\"${it.value}\"" } + ",le=\"${entry.key}\"}"
                    } else {
                        "{le=\"${entry.key}\"}"
                    }
                    builder.append("${metric.name}_bucket$bucketTags ${entry.value}\n")
                }
                val infTags = if (metric.tags.isNotEmpty()) {
                    "{" + metric.tags.entries.joinToString(",") { "${it.key}=\"${it.value}\"" } + ",le=\"+Inf\"}"
                } else {
                    "{le=\"+Inf\"}"
                }
                builder.append("${metric.name}_bucket$infTags ${metric.getCount()}\n")
                builder.append("${metric.name}_sum$tagsString ${metric.getSum()}\n")
                builder.append("${metric.name}_count$tagsString ${metric.getCount()}\n")
            } else if (metric is Timer) {
                builder.append("# TYPE ${metric.name} summary\n")
                builder.append("${metric.name}_count$tagsString ${metric.getCount()}\n")
                builder.append("${metric.name}_sum$tagsString ${metric.getTotalTime().inWholeNanoseconds.toDouble() / 1_000_000_000.0}\n")
            }
        }
        
        return builder.toString()
    }
}

class JsonExporter : MetricsExporter {
    override fun export(metrics: List<Metric>): String {
        val builder = StringBuilder()
        builder.append("[\n")
        
        var i = 0
        for (metric in metrics) {
            builder.append("  {\n")
            builder.append("    \"name\": \"${metric.name}\",\n")
            
            val tagsJson = metric.tags.entries.joinToString(", ") { "\"${it.key}\": \"${it.value}\"" }
            builder.append("    \"tags\": { $tagsJson },\n")
            
            if (metric is Counter) {
                builder.append("    \"type\": \"counter\",\n    \"value\": ${metric.get()}\n")
            } else if (metric is Gauge) {
                builder.append("    \"type\": \"gauge\",\n    \"value\": ${metric.get()}\n")
            } else if (metric is Histogram) {
                builder.append("    \"type\": \"histogram\",\n")
                builder.append("    \"count\": ${metric.getCount()},\n")
                builder.append("    \"sum\": ${metric.getSum()},\n")
                val bucketsJson = metric.getBuckets().entries.joinToString(", ") { "\"${it.key}\": ${it.value}" }
                builder.append("    \"buckets\": { $bucketsJson }\n")
            } else if (metric is Timer) {
                builder.append("    \"type\": \"timer\",\n")
                builder.append("    \"count\": ${metric.getCount()},\n")
                builder.append("    \"total_time_seconds\": ${metric.getTotalTime().inWholeNanoseconds.toDouble() / 1_000_000_000.0}\n")
            }
            
            if (i < metrics.size - 1) {
                builder.append("  },\n")
            } else {
                builder.append("  }\n")
            }
            i++
        }
        
        builder.append("]")
        return builder.toString()
    }
}

class OpenTelemetryExporter : MetricsExporter {
    override fun export(metrics: List<Metric>): String {
        return "{\"resourceMetrics\": [{\"instrumentationLibraryMetrics\": [{\"metrics\": " + JsonExporter().export(metrics) + "}]}]}"
    }
}
