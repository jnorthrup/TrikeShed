package borg.trikeshed.tilting.leaf

import borg.trikeshed.tilting.lz4.Lz4BlockOps
import borg.trikeshed.tilting.lzo.LzoBlockOps
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform

/**
 * Every native target (TrikeShed's macos, linux and mingwX64; the challenge slice's linuxX64, linuxArm64 and macosArm64)
 * binds no C library for the leaf codecs: the Kotlin LZ4 and LZO of commonMain are the platform's, as on WASI. No
 * cinterop, no C header, no liblz4 or liblzo2 at link or run time. [platform] is the target's OS and CPU, e.g.
 * linux-x64, macosx-arm64.
 */
actual object LeafSpecialization {
    @OptIn(ExperimentalNativeApi::class)
    actual val platform: String =
        "${Platform.osFamily.name.lowercase()}-${Platform.cpuArchitecture.name.lowercase()}"
    actual fun lz4(): Lz4BlockOps? = null
    actual fun lzo(): LzoBlockOps? = null
}
