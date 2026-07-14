package borg.trikeshed.job

import java.security.MessageDigest

actual fun sha256(bytes: ByteArray): ByteArray {
    return MessageDigest.getInstance("SHA-256").digest(bytes)
}