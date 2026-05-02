package borg.trikeshed.cpucache

import java.io.File

/**
 * JVM cache interrogation — reads /sys on Linux, sysctl on macOS,
 * falls back to UNKNOWN otherwise.
 */
actual fun interrogateCpuCache(): CpuCacheTopology {
    val os = System.getProperty("os.name").lowercase()
    return when {
        os.contains("linux") -> interrogateLinux()
        os.contains("mac") -> interrogateMacOs()
        else -> interrogatePosixFallback()
    }
}

private fun interrogateLinux(): CpuCacheTopology {
    val base = "/sys/devices/system/cpu/cpu0/cache"
    val dir = File(base)
    if (!dir.isDirectory) return CpuCacheTopology(null, null, null, null, null, null)

    var l1d: Long? = null
    var l1i: Long? = null
    var l2: Long? = null
    var l3: Long? = null
    var line: Int? = null

    dir.listFiles()?.filter { it.isDirectory }?.forEach { indexDir ->
        val type = readFile(File(indexDir, "type"))?.trim()
        val level = readFile(File(indexDir, "level"))?.trim()?.toIntOrNull()
        val size = readFile(File(indexDir, "size"))?.trim()?.parseSize()
        if (line == null) {
            readFile(File(indexDir, "coherency_line_size"))?.trim()?.toIntOrNull()?.let { line = it }
        }
        when {
            level == 1 && type == "Data" -> l1d = size
            level == 1 && type == "Instruction" -> l1i = size
            level == 2 -> l2 = size
            level == 3 -> l3 = size
        }
    }

    val cores = Runtime.getRuntime().availableProcessors()
    return CpuCacheTopology(l1d, l1i, l2, l3, line, cores)
}

private fun interrogateMacOs(): CpuCacheTopology {
    fun sysctlLong(key: String): Long? = try {
        ProcessBuilder("sysctl", "-n", key)
            .redirectErrorStream(true).start()
            .inputStream.bufferedReader().readText().trim().toLongOrNull()
    } catch (_: Exception) { null }
    fun sysctlInt(key: String): Int? = try {
        ProcessBuilder("sysctl", "-n", key)
            .redirectErrorStream(true).start()
            .inputStream.bufferedReader().readText().trim().toIntOrNull()
    } catch (_: Exception) { null }

    return CpuCacheTopology(
        l1DataBytes = sysctlLong("hw.l1dcachesize"),
        l1InstructionBytes = sysctlLong("hw.l1icachesize"),
        l2Bytes = sysctlLong("hw.l2cachesize"),
        l3Bytes = sysctlLong("hw.l3cachesize"),
        cacheLineBytes = sysctlInt("hw.cachelinesize"),
        coreCount = sysctlInt("hw.ncpu"),
    )
}

private fun readFile(file: File): String? =
    if (file.isFile && file.canRead()) file.readText() else null

/** Parse a size string like "32K" or "256K" or "8M" to bytes. */
private fun String.parseSize(): Long? {
    val trimmed = this.trim()
    if (trimmed.isEmpty()) return null
    val multiplier = when (trimmed.last().uppercaseChar()) {
        'K' -> 1024L
        'M' -> 1024L * 1024
        'G' -> 1024L * 1024 * 1024
        else -> return trimmed.toLongOrNull()
    }
    return trimmed.dropLast(1).toLongOrNull()?.times(multiplier)
}

/**
 * Generic POSIX fallback using sysconf() via JNI.
 * Attempts to load the native library and call sysconf directly.
 * Falls back to Runtime.availableProcessors() for core count.
 */
private fun interrogatePosixFallback(): CpuCacheTopology {
    return try {
        SysconfInterop.interrogateSysconf()
    } catch (e: UnsatisfiedLinkError) {
        // JNI library not available, use runtime values only
        CpuCacheTopology(
            l1DataBytes = null,
            l1InstructionBytes = null,
            l2Bytes = null,
            l3Bytes = null,
            cacheLineBytes = null,
            coreCount = Runtime.getRuntime().availableProcessors()
        )
    } catch (e: Exception) {
        // Any other error, return partial topology
        CpuCacheTopology(
            l1DataBytes = null,
            l1InstructionBytes = null,
            l2Bytes = null,
            l3Bytes = null,
            cacheLineBytes = null,
            coreCount = Runtime.getRuntime().availableProcessors()
        )
    }
}
