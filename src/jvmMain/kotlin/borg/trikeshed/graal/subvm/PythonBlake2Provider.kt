package borg.trikeshed.graal.subvm

import org.bouncycastle.crypto.digests.Blake2bDigest
import java.security.MessageDigestSpi
import java.security.Provider

/** GraalPy 25.3 sends byte lengths in JCA's bit-length algorithm names. */
object PythonBlake2Provider : Provider("TrikeShedGraalPyBlake2", "1.0", "GraalPy BLAKE2b byte-length bridge") {
    init {
        for (bytes in 1..64) putService(object : Service(this, "MessageDigest", "BLAKE2B-$bytes",
            Digest::class.java.name, emptyList(), emptyMap()) {
            override fun newInstance(constructorParameter: Any?): Any {
                require(constructorParameter == null)
                return Digest(bytes)
            }
        })
    }

    class Digest(bytes: Int) : MessageDigestSpi(), Cloneable {
        private var digest = Blake2bDigest(bytes * 8)
        override fun engineGetDigestLength(): Int = digest.digestSize
        override fun engineUpdate(input: Byte) = digest.update(input)
        override fun engineUpdate(input: ByteArray, offset: Int, len: Int) = digest.update(input, offset, len)
        override fun engineDigest(): ByteArray = ByteArray(digest.digestSize).also { digest.doFinal(it, 0) }
        override fun engineReset() = digest.reset()
        public override fun clone(): Any = (super<MessageDigestSpi>.clone() as Digest).also { it.digest = Blake2bDigest(digest) }
    }
}
