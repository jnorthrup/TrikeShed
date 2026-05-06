@file:Suppress("NonAsciiCharacters")
package borg.trikeshed.lib
import java.io.File
actual val platformCacheTopology: CacheTopology by lazy {
    val os = System.getProperty("os.name").lowercase()
    when { os.contains("linux") -> interrogateLinux(); os.contains("mac") -> interrogateMacOs(); else -> CacheTopology.UNKNOWN }
}
private fun interrogateLinux(): CacheTopology {
    val base = File("/sys/devices/system/cpu/cpu0/cache")
    if (!base.isDirectory) return CacheTopology.UNKNOWN
    var l1d: Long? = null; var l1i: Long? = null; var l2: Long? = null; var l3: Long? = null; var line: Int? = null
    base.listFiles()?.filter { it.isDirectory }?.forEach { d ->
        val t = readFile(File(d, "type"))?.trim(); val l = readFile(File(d, "level"))?.trim()?.toIntOrNull()
        val s = readFile(File(d, "size"))?.trim()?.parseKb()
        if (line == null) line = readFile(File(d, "coherency_line_size"))?.trim()?.toIntOrNull()
        when { l == 1 && t == "Data" -> l1d = s; l == 1 && t == "Instruction" -> l1i = s; l == 2 -> l2 = s; l == 3 -> l3 = s }
    }
    return CacheTopology(l1d, l1i, l2, l3, line, Runtime.getRuntime().availableProcessors())
}
private fun readFile(f: File) = if (f.isFile && f.canRead()) f.readText() else null
private fun String.parseKb(): Long? { val s = trim(); if (s.isEmpty()) return null; val m = when (s.last().uppercaseChar()) { 'K' -> 1024L; 'M' -> 1024L*1024; 'G' -> 1024L*1024*1024; else -> return s.toLongOrNull() }; return s.dropLast(1).toLongOrNull()?.times(m) }
private fun interrogateMacOs(): CacheTopology {
    fun sysctl(k: String) = try { ProcessBuilder("sysctl","-n",k).redirectErrorStream(true).start().inputStream.bufferedReader().readText().trim() } catch (_: Exception) { null }
    return CacheTopology(sysctl("hw.l1dcachesize")?.toLongOrNull(), sysctl("hw.l1icachesize")?.toLongOrNull(), sysctl("hw.l2cachesize")?.toLongOrNull(), sysctl("hw.l3cachesize")?.toLongOrNull(), sysctl("hw.cachelinesize")?.toIntOrNull(), sysctl("hw.ncpu")?.toIntOrNull())
}
