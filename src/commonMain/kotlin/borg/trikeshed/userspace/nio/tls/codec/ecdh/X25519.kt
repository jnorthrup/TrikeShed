package borg.trikeshed.userspace.nio.tls.codec.ecdh

import kotlin.coroutines.CoroutineContext

/**
 * X25519 elliptic-curve Diffie-Hellman (RFC 7748).
 *
 * Used by TLS 1.3 for key exchange. The client generates a key pair,
 * sends the public key in the `key_share` extension, and the server
 * responds with its public key. Both sides then compute the shared secret.
 *
 * CCEK: platform engines register their X25519 implementation in the context.
 */
interface X25519 : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<X25519>

    /** Public + private key pair. Both are 32 bytes. */
    data class KeyPair(val publicKey: ByteArray, val privateKey: ByteArray)

    /** Generate a fresh ephemeral key pair. */
    fun generateKeyPair(): KeyPair

    /**
     * Compute the shared secret from our private key and peer's public key.
     * Returns 32-byte shared secret (the X-coordinate of the ECDH result).
     */
    fun sharedSecret(privateKey: ByteArray, peerPublicKey: ByteArray): ByteArray
}
