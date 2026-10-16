package borg.trikeshed.tilting.leaf

import borg.trikeshed.tilting.lz4.Lz4BlockOps
import borg.trikeshed.tilting.lz4.Lz4Ffm
import borg.trikeshed.tilting.lzo.LzoBlockOps
import borg.trikeshed.tilting.lzo.LzoFfm

/**
 * JVM specialization: liblz4 and liblzo2 through FFM. [platform] is graal-native inside a GraalVM native image built
 * from this code, jvm otherwise.
 */
actual object LeafSpecialization {
    actual val platform: String =
        if (System.getProperty("org.graalvm.nativeimage.imagecode") == "runtime") "graal-native" else "jvm"
    actual fun lz4(): Lz4BlockOps? = Lz4Ffm.loadOrNull()
    actual fun lzo(): LzoBlockOps? = LzoFfm.loadOrNull()
}
