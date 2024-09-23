package borg.trikeshed.lib

import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen

val L1CacheSizeInInts: Long by lazy {
    val fopen = fopen("/sys/devices/system/cpu/cpu0/cache/index0/size", "r")
    val fgets = fgets(memScoped { allocArray(1024) }, 1024, fopen)
    fclose(fopen)

    // how many ints fit l1 cache ?

    val l1CacheSize = fgets?.toKString()?.readableUnitsToNumber() ?: 64

    return@lazy     l1CacheSize.toLong() / 4

}