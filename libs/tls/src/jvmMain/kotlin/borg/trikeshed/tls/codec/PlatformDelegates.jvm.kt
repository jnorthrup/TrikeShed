package borg.trikeshed.tls.codec

import borg.trikeshed.tls.codec.aead.Aes128Gcm
import borg.trikeshed.tls.codec.aead.JvmAes128Gcm
import borg.trikeshed.tls.codec.ecdh.X25519
import borg.trikeshed.tls.codec.ecdh.JvmX25519
import borg.trikeshed.tls.codec.hash.Sha256
import borg.trikeshed.tls.codec.hash.JvmSha256

actual fun loadPlatformAes128Gcm(): Aes128Gcm? = JvmAes128Gcm()
actual fun loadPlatformX25519(): X25519? = JvmX25519()
actual fun loadPlatformSha256(): Sha256? = JvmSha256()
