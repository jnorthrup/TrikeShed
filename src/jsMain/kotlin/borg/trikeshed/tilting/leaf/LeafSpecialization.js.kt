package borg.trikeshed.tilting.leaf

import borg.trikeshed.tilting.lz4.Lz4BlockOps
import borg.trikeshed.tilting.lzo.LzoBlockOps

/** Kotlin/JS binds no C library: the kotlin implementations are the platform's. */
actual object LeafSpecialization {
    actual val platform: String get() = "js"
    actual fun lz4(): Lz4BlockOps? = null
    actual fun lzo(): LzoBlockOps? = null
}
