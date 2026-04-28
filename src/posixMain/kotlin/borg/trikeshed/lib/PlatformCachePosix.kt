@file:OptIn(ExperimentalForeignApi::class)
@file:Suppress("NonAsciiCharacters")

package borg.trikeshed.lib

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen

/**
 * Reads L1 data cache size from `/sys/devices/system/cpu/cpu0/cache/index0/size`
 * and cache line size from `.../coherency_line_size`.
 */
actual val platformCacheTopology: CacheTopology by lazy {
    val l1Bytes = readSysFsCacheFile("index0/size")?.readableUnitsToNumber()?.toLong()
    val lineBytes = readSysFsCacheFile("index0/coherency_line_size")?.readableUnitsToNumber()?.toInt()
    if (l1Bytes != null) CacheTopology(l1Bytes, lineBytes) else CacheTopology.UNKNOWN
}

private fun readSysFsCacheFile(suffix: String): String? {
    val path = "/sys/devices/system/cpu/cpu0/cache/$suffix"
    val fp = fopen(path, "r") ?: return null
    val buf = memScoped { allocArray(256) }
    val result = fgets(buf, 256, fp)
    fclose(fp)
    return result?.toKString()?.trim()
}
