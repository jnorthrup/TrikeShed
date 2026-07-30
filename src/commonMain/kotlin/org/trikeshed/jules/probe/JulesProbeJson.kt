package org.trikeshed.jules.probe

import borg.trikeshed.parse.json.JsonSupport

object JulesProbeJson {
    fun encode(handle: ProbeHandle): String = ""
    fun decode(json: String): ProbeHandle {
        val root = JsonSupport.parse(json) as? Map<*, *> ?: emptyMap<String, Any?>()
        val id = root["id"] as? String ?: ""
        val metricsRaw = root["metrics"] as? List<*> ?: emptyList<Any?>()
        
        val metricList = metricsRaw.mapNotNull { 
            it as? Map<*, *> ?: return@mapNotNull null
            val timestamp = (it["timestamp"] as? Number)?.toLong() ?: 0L
            val value = (it["value"] as? Number)?.toDouble() ?: 0.0
            ProbeMetric(timestamp, value)
        }
        
        val metricsSeries = object : Series<ProbeMetric> {
            override val size: Int = metricList.size
            override fun get(index: Int): ProbeMetric = metricList[index]
        }
        
        return ProbeHandle(id, metricsSeries)
    }
}
