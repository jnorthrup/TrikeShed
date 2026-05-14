package borg.trikeshed.tls.codec.aead

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * JVM AES-128-GCM via javax.crypto (AES/GCM/NoPadding).
 */
class JvmAes128Gcm : borg.trikeshed.userspace.nio.tls.codec.aead.Aes128Gcm {
    override val key: kotlin.coroutines.CoroutineContext.Key<*> get() = _root_ide_package_.borg.trikeshed.userspace.nio.tls.codec.aead.Aes128Gcm.Key

    override fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(tagLength * 8, nonce)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    override fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray? {
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val keySpec = SecretKeySpec(key, "AES")
            val gcmSpec = GCMParameterSpec(tagLength * 8, nonce)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
            if (aad.isNotEmpty()) cipher.updateAAD(aad)
            cipher.doFinal(ciphertext)
        } catch (_: Exception) { null }
    }
}

/**
 * JVM ChaCha20-Poly1305 via javax.crypto (ChaCha20-Poly1305/None/NoPadding).
 * Requires Java 11+.
 */
class JvmChaCha20Poly1305 : borg.trikeshed.userspace.nio.tls.codec.aead.ChaCha20Poly1305 {
    override val key: kotlin.coroutines.CoroutineContext.Key<*> get() = _root_ide_package_.borg.trikeshed.userspace.nio.tls.codec.aead.ChaCha20Poly1305.Key

    override fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("ChaCha20-Poly1305/None/NoPadding")
        val keySpec = SecretKeySpec(key, "ChaCha20")
        val paramSpec = javax.crypto.spec.IvParameterSpec(nonce)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, paramSpec)
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    override fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray? {
        return try {
            val cipher = Cipher.getInstance("ChaCha20-Poly1305/None/NoPadding")
            val keySpec = SecretKeySpec(key, "ChaCha20")
            val paramSpec = javax.crypto.spec.IvParameterSpec(nonce)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, paramSpec)
            if (aad.isNotEmpty()) cipher.updateAAD(aad)
            cipher.doFinal(ciphertext)
        } catch (_: Exception) { null }
    }
}
