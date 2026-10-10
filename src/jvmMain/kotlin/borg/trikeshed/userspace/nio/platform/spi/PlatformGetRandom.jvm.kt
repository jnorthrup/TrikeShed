package borg.trikeshed.userspace.nio.platform.spi

import java.security.*

val SECURE_RANDOM = SecureRandom()

actual fun platformGetRandom(bytes: ByteArray) = SECURE_RANDOM.nextBytes(bytes)
