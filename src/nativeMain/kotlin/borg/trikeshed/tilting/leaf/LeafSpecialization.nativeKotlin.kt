package borg.trikeshed.tilting.leaf

import borg.trikeshed.tilting.lz4.Lz4BlockOps
import borg.trikeshed.tilting.lzo.LzoBlockOps
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform

/**
 * TrikeShed's own native targets (macos, linux, mingwX64) bind no C library here: the kotlin implementations are the
 * platform's. src/lzNativeMain holds the liblz4 / liblzo2 specialization through cinterop
 * (src/nativeInterop/cinterop/lz.def); a build that wires lzNativeMain compiles that file instead of this one.
 */
actual object LeafSpecialization {
    @OptIn(ExperimentalNativeApi::class)
    actual val platform: String =
        "${Platform.osFamily.name.lowercase()}-${Platform.cpuArchitecture.name.lowercase()}"
    actual fun lz4(): Lz4BlockOps? = null
    actual fun lzo(): LzoBlockOps? = null
}
