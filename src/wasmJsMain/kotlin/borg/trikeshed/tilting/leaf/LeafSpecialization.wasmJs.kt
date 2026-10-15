package borg.trikeshed.tilting.leaf

import borg.trikeshed.tilting.lz4.Lz4BlockOps
import borg.trikeshed.tilting.lzo.LzoBlockOps

/** Kotlin/Wasm (JS host) binds no C library: the kotlin implementations are the platform's. */
actual object LeafSpecialization {
    actual val platform: String get() = "wasm-js"
    actual fun lz4(): Lz4BlockOps? = null
    actual fun lzo(): LzoBlockOps? = null
}
