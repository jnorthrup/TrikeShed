package borg.trikeshed.tls.codec

import borg.trikeshed.tls.codec.aead.Aes128Gcm
import borg.trikeshed.tls.codec.ecdh.X25519
import borg.trikeshed.tls.codec.hash.Sha256

actual fun loadPlatformAes128Gcm(): Aes128Gcm? = null
actual fun loadPlatformX25519(): X25519? = null
actual fun loadPlatformSha256(): Sha256? = null
