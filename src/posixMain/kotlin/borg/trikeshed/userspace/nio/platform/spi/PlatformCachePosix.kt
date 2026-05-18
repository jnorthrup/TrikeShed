@file:OptIn(ExperimentalForeignApi::class)
@file:Suppress("NonAsciiCharacters")
package borg.trikeshed.userspace.nio.platform.spi
import borg.trikeshed.lib.readableUnitsToNumber
import kotlinx.cinterop.*
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen

@OptIn(ExperimentalForeignApi::class)
actual val platformCacheTopology: CacheTopology by lazy {
    val fromSys = readFromSysFs()
    if (fromSys != null) return@lazy fromSys
    CacheTopology.UNKNOWN
}
private fun readSysFsCacheFile(suffix: String): String? { val p = "/sys/devices/system/cpu/cpu0/cache/$suffix"; val fp = fopen(p, "r") ?: return null; val b = memScoped { allocArray<ByteVar>(256) }; val r = fgets(b, 256, fp); fclose(fp); return r?.toKString()?.trim() }
private fun readFromSysFs(): CacheTopology? {
    if (readSysFsCacheFile("index0/type") == null) return null
    var l1d: Long? = null; var l1i: Long? = null; var l2: Long? = null; var l3: Long? = null; var line: Int? = null
    for (i in 0..3) { val p = "index$i"; val t = readSysFsCacheFile("$p/type")?.trim() ?: break; val l = readSysFsCacheFile("$p/level")?.trim()?.toIntOrNull() ?: break; val s = readSysFsCacheFile("$p/size")?.trim()?.readableUnitsToNumber()?.toLong(); if (line == null) line = readSysFsCacheFile("$p/coherency_line_size")?.trim()?.toIntOrNull(); when { l == 1 && t == "Data" -> l1d = s; l == 1 && t == "Instruction" -> l1i = s; l == 2 -> l2 = s; l == 3 -> l3 = s } }
    return CacheTopology(l1d, l1i, l2, l3, line, null)
}
