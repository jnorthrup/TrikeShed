package borg.trikeshed.web.spacegraph

import borg.trikeshed.lib.*

/** Text from a retained invocation; reading it never enters the guest or infers missing counters. */
internal fun llvmObservation(runtime: dynamic): List<String> {
    fun value(name: String): String = if (typeOf(runtime[name]) == "string" && str(runtime[name]).isNotBlank())
        str(runtime[name]).take(256) else "unavailable"
    fun count(name: String): String {
        val n = runtime[name]
        return if (typeOf(n) == "number" && num(n).isFinite() && num(n) >= 0) str(n) else "unavailable"
    }
    fun duration(name: String): String {
        val n = runtime[name]
        if (typeOf(n) != "number" || !num(n).isFinite() || num(n) < 0) return "unavailable"
        return when {
            num(n) >= 1_000_000_000 -> fixed(num(n) / 1_000_000_000, 3) + " s"
            num(n) >= 1_000_000 -> fixed(num(n) / 1_000_000, 3) + " ms"
            num(n) >= 1_000 -> fixed(num(n) / 1_000, 3) + " µs"
            else -> str(n) + " ns"
        }
    }
    val lines = mutableListOf(
        "Recorded LLVM observation · " + value("abi"),
        "Module: " + value("moduleCid"),
        "GraalVM " + value("engineVersion") + " · LLVM " + value("llvmVersion"),
        "Queue " + duration("queueNanos") + " · call " + duration("callNanos") + " · load " + duration("loadNanos"),
        "Internal progress: " + value("internalProgress") + " · pause: " + value("pauseBoundary"),
    )
    if (runtime.abi == "confix.flat/1") {
        lines.add("Scanner " + duration("scanNanos") + " · row capacity " + count("rowCapacity"))
        lines.add("Owned native allocation " + count("allocatedBytes") + " bytes · after release " + count("liveBytesAfter") + " bytes")
        lines.add("Buffer guards: " + when (runtime.guardsVerified) { true -> "verified"; false -> "failed"; else -> "unavailable" })
        lines.add("Allocation counters cover the bridge buffer, not the VM heap. Scanner success is not strict validation.")
    } else lines.add("Export: " + value("export") + ". Native allocation counters were not recorded by this ABI.")
    lines.add("Retained measurements, not a live VM. No native-memory isolation or hard native cancellation is established.")
    return lines
}
