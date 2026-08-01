package org.trikeshed.jules.probe

import borg.trikeshed.lib.Series

interface JulesProbe {
    fun start(): ProbeHandle
    fun stop()
}

data class ProbeHandle(val id: String, val metrics: Series<ProbeMetric>)

data class ProbeMetric(val timestamp: Long, val value: Double)

fun ProbeHandle.decode(json: String): ProbeHandle = JulesProbeJson.decode(json)
