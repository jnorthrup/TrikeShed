package borg.trikeshed.job

actual fun sha256(bytes: ByteArray): ByteArray = Sha256Pure.digest(bytes)
