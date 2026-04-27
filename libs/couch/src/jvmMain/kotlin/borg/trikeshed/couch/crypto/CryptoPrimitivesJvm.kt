package borg.trikeshed.couch.crypto

import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.KeyPairGenerator
import java.security.spec.XECPublicKeySpec
import java.security.spec.XECPrivateKeySpec
import java.security.spec.NamedParameterSpec
import java.math.BigInteger

// ── X25519 (RFC 7748) ────────────────────────────────────────────────────────────

actual class X25519PrivateKey actual constructor(raw: ByteArray) {
    actual val raw: ByteArray = raw.copyOf()
}

actual class X25519PublicKey actual constructor(raw: ByteArray) {
    actual val raw: ByteArray = raw.copyOf()
}

actual fun x25519GenerateKeyPair(): Pair<X25519PrivateKey, X25519PublicKey> {
    val gen = KeyPairGenerator.getInstance("X25519")
    val kp = gen.generateKeyPair()
    val priv = X25519PrivateKey(kp.private.encoded)
    val pub = X25519PublicKey(kp.public.encoded)
    return priv to pub
}

actual fun x25519Dh(ours: X25519PrivateKey, theirs: X25519PublicKey): ByteArray {
    val spec = NamedParameterSpec("X25519")
    val kf = java.security.KeyFactory.getInstance("X25519")
    val privKey = kf.generatePrivate(XECPrivateKeySpec(spec, ours.raw))
    val pubKey = kf.generatePublic(XECPublicKeySpec(spec, BigInteger(1, theirs.raw)))
    val ka = KeyAgreement.getInstance("X25519")
    ka.init(privKey)
    ka.doPhase(pubKey, true)
    return ka.generateSecret()
}

// ── HKDF-SHA256 (RFC 5869) ───────────────────────────────────────────────────────

actual fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(salt, "HmacSHA256"))
    return mac.doFinal(ikm)
}

actual fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
    require(length <= 255 * 32) { "HKDF-Expand length $length exceeds 8160 byte maximum" }
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(prk, "HmacSHA256"))
    val result = ByteArray(length)
    var tPrev = ByteArray(0)
    var written = 0
    var blockIdx = 1
    while (written < length) {
        mac.reset()
        if (tPrev.isNotEmpty()) mac.update(tPrev)
        mac.update(info)
        mac.update(blockIdx.toByte())
        tPrev = mac.doFinal()
        val toCopy = minOf(tPrev.size, length - written)
        System.arraycopy(tPrev, 0, result, written, toCopy)
        written += toCopy
        blockIdx++
    }
    return result
}

// ── AES-256-GCM (NIST SP 800-38D) ────────────────────────────────────────────────

private const val GCM_TAG_LEN = 16

actual fun aes256GcmEncrypt(
    key: ByteArray,
    nonce: ByteArray,
    plaintext: ByteArray,
    aad: ByteArray,
): AesGcmCiphertext {
    require(key.size == 32) { "AES-256 requires 32-byte key, got ${key.size}" }
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    val spec = GCMParameterSpec(GCM_TAG_LEN * 8, nonce)
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), spec)
    if (aad.isNotEmpty()) cipher.updateAAD(aad)
    val output = cipher.doFinal(plaintext)
    // Output is ciphertext || tag (last GCM_TAG_LEN bytes)
    val ct = output.copyOfRange(0, output.size - GCM_TAG_LEN)
    val tag = output.copyOfRange(output.size - GCM_TAG_LEN, output.size)
    return AesGcmCiphertext(ct, tag)
}

actual fun aes256GcmDecrypt(
    key: ByteArray,
    nonce: ByteArray,
    ciphertext: ByteArray,
    tag: ByteArray,
    aad: ByteArray,
): ByteArray? {
    require(key.size == 32) { "AES-256 requires 32-byte key, got ${key.size}" }
    require(tag.size == GCM_TAG_LEN) { "GCM tag must be $GCM_TAG_LEN bytes, got ${tag.size}" }
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    val spec = GCMParameterSpec(GCM_TAG_LEN * 8, nonce)
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), spec)
    if (aad.isNotEmpty()) cipher.updateAAD(aad)
    cipher.update(ciphertext)
    return try {
        cipher.doFinal(tag) // verifies tag in-place
    } catch (e: javax.crypto.AEADBadTagException) {
        null
    }
}
