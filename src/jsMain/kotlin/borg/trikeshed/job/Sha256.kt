package borg.trikeshed.job

/** JS implementation of SHA-256 — delegates to the pure Kotlin implementation. */
actual fun sha256(bytes: ByteArray): ByteArray = Sha256Pure.digest(bytes)
