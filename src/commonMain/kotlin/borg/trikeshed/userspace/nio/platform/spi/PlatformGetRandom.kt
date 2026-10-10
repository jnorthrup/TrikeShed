package borg.trikeshed.userspace.nio.platform.spi

/**
 * Fills [bytes] from the operating system CSPRNG: SecureRandom on the JVM, getentropy(2) on macOS,
 * getrandom(2) on Linux, crypto.getRandomValues in JS and wasm.
 */
expect fun platformGetRandom(bytes: ByteArray)
