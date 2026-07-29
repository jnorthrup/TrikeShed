package org.trikeshed.jules.probe

object JsonSupport {
    fun encode(handle: ProbeHandle): String = ""
    fun decode(json: String): ProbeHandle = TODO("Implement decode")
}

object JulesProbeJson {
    fun encode(handle: ProbeHandle): String = JsonSupport.encode(handle)
    fun decode(json: String): ProbeHandle = JsonSupport.decode(json)
}
