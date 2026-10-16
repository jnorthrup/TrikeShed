package borg.trikeshed.graal.subvm

import java.lang.management.ManagementFactory

/** Measured host invariants; absent hardware facts remain unavailable to the guest. */
object PythonHostFacts {
    fun snapshot(): Map<String, Any?> = buildMap {
        val os = System.getProperty("os.name").lowercase()
        put("os_family", when {
            os.contains("mac") || os.contains("darwin") -> "darwin"
            os.contains("windows") -> "win32"
            os.contains("linux") -> "linux"
            else -> os
        })
        put("process_arch", System.getProperty("os.arch"))
        val process = ProcessHandle.current()
        put("process_id", process.pid())
        process.info().startInstant().orElse(null)?.let { put("process_start_time", it.toEpochMilli() / 1000.0) }
        val memory = runCatching {
            (ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)?.totalMemorySize
        }.getOrNull()
        if (memory != null && memory > 0) put("ram_total_bytes", memory)
    }
}
