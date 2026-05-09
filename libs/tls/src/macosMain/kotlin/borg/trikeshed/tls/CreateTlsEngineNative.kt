package borg.trikeshed.tls

import borg.trikeshed.tls.codec.CommonTlsRecordCodec
import borg.trikeshed.tls.codec.aead.DefaultAes128Gcm
import borg.trikeshed.tls.record.ContentType

actual fun createTlsEngine(settings: TlsSettings): TlsEngine {
    val aes = DefaultAes128Gcm()
    val codec = CommonTlsRecordCodec(aes)
    return NativeTlsEngine(codec)
}

private class NativeTlsEngine(
    private val codec: CommonTlsRecordCodec,
) : TlsEngine {
    override suspend fun wrap(plain: ByteArray): ByteArray =
        codec.encrypt(
            borg.trikeshed.tls.codec.RecordDirection.CLIENT_WRITE,
            ContentType.APPLICATION_DATA,
            plain,
        )

    override suspend fun unwrap(encrypted: ByteArray): ByteArray =
        codec.decrypt(
            borg.trikeshed.tls.codec.RecordDirection.SERVER_WRITE,
            encrypted,
        ) ?: error("TLS decrypt failed")

    override suspend fun close() {}
}
