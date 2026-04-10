package borg.literbike.ccek.quic.tls_crypto

// ============================================================================
// Crypto Provider -- ported from tls_crypto/provider.rs (simplified)
// Rustls-based QUIC crypto provider
// ============================================================================

import borg.literbike.ccek.quic.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Rustls-based QUIC crypto provider.
 * Ported from Rust RustlsCryptoProvider.
 *
 * In production, integrate with Bouncy Castle TLS or your platform TLS.
 * This stub provides the interface structure with placeholder implementations.
 */
class RustlsCryptoProvider(
    private val clientDcid: ByteArray
) : QuicCryptoProvider {
    var initialState: QuicCryptoState? = null
        private set

    private var handshakeState: QuicCryptoState? = null
    private var onerttState: QuicCryptoState? = null

    private var phase: HandshakePhase = HandshakePhase.Initial

    // Crypto receive buffers (offset -> data)
    private val cryptoRxBufferInitial = mutableMapOf<ULong, ByteArray>()
    private var cryptoRxNextInitial: ULong = 0uL
    private val cryptoRxBufferHandshake = mutableMapOf<ULong, ByteArray>()
    private var cryptoRxNextHandshake: ULong = 0uL

    private var cryptoFailed = false
    private var clientFinishedReceived = false

    private val pendingInitialWrite = ConcurrentLinkedQueue<ByteArray>()
    private val pendingHandshakeWrite = ConcurrentLinkedQueue<ByteArray>()

    companion object {
        /** Create a server-side crypto provider */
        fun newServer(clientDstConnId: ByteArray): Result<RustlsCryptoProvider> = runCatching {
            val provider = RustlsCryptoProvider(clientDstConnId)

            // Derive server initial keys
            val (_, serverInitial) = deriveInitialSecrets(clientDstConnId)
            val (key, iv, hp) = derivePacketProtectionKeys(serverInitial)

            provider.initialState = QuicCryptoState.create(
                algorithm = QuicAeadAlgorithm.Aes128Gcm,
                keyBytes = key,
                iv = iv,
                hpKeyBytes = hp
            ).getOrThrow()

            provider
        }

        /** Create a client-side crypto provider */
        fun newClient(): Result<RustlsCryptoProvider> = runCatching {
            val dummyDcid = ByteArray(8)  // Will be set from server's response
            RustlsCryptoProvider(dummyDcid)
        }
    }

    override fun onCryptoFrame(frame: CryptoFrame, level: borg.literbike.ccek.quic.EncryptionLevel, state: borg.literbike.ccek.quic.QuicConnectionState): Result<borg.literbike.ccek.quic.CryptoFrameDisposition> {
        if (cryptoFailed) return Result.success(borg.literbike.ccek.quic.CryptoFrameDisposition.AckOnly)

        val (buf, nextRef) = when (level) {
            borg.literbike.ccek.quic.EncryptionLevel.Initial -> cryptoRxBufferInitial to { cryptoRxNextInitial }
            borg.literbike.ccek.quic.EncryptionLevel.Handshake -> cryptoRxBufferHandshake to { cryptoRxNextHandshake }
            borg.literbike.ccek.quic.EncryptionLevel.OneRtt -> {
                return Result.success(borg.literbike.ccek.quic.CryptoFrameDisposition.ProgressedHandshake)
            }
        }

        val next = nextRef()
        val frameEnd = frame.offset + frame.data.size.toULong()
        if (frameEnd <= next) {
            return Result.success(borg.literbike.ccek.quic.CryptoFrameDisposition.ProgressedHandshake)
        }

        val trimmedOffset = maxOf(frame.offset, next)
        val trim = (trimmedOffset - frame.offset).toInt()
        val trimmedData = frame.data.subList(trim, frame.data.size).toByteArray()

        buf[trimmedOffset] = trimmedData

        // Process contiguous data
        while (buf.containsKey(next)) {
            val data = buf.remove(next)!!
            // In production: feed to TLS handshake processor
            println("TLS: Processing ${data.size} bytes of handshake data at offset $next")

            when (level) {
                borg.literbike.ccek.quic.EncryptionLevel.Initial -> cryptoRxNextInitial += data.size.toULong()
                borg.literbike.ccek.quic.EncryptionLevel.Handshake -> cryptoRxNextHandshake += data.size.toULong()
                else -> {}
            }
        }

        // Update handshake phase based on progress
        if (cryptoRxNextHandshake > 0uL) {
            phase = HandshakePhase.Handshaking
        }

        Result.success(borg.literbike.ccek.quic.CryptoFrameDisposition.ProgressedHandshake)
    }

    override fun handshakePhase(): HandshakePhase = phase

    override fun headerProtectionReady(): Boolean = initialState != null

    override fun handshakeComplete(): Boolean = phase == HandshakePhase.OneRtt

    override fun drainCryptoWrites(): List<borg.literbike.ccek.quic.CryptoWrite> {
        val writes = mutableListOf<borg.literbike.ccek.quic.CryptoWrite>()

        while (pendingInitialWrite.isNotEmpty()) {
            writes.add(borg.literbike.ccek.quic.CryptoWrite(
                level = borg.literbike.ccek.quic.EncryptionLevel.Initial,
                data = pendingInitialWrite.poll().toList().map { it.toUByte() }
            ))
        }

        while (pendingHandshakeWrite.isNotEmpty()) {
            writes.add(borg.literbike.ccek.quic.CryptoWrite(
                level = borg.literbike.ccek.quic.EncryptionLevel.Handshake,
                data = pendingHandshakeWrite.poll().toList().map { it.toUByte() }
            ))
        }

        return writes
    }

    override fun decryptPacket(
        level: borg.literbike.ccek.quic.EncryptionLevel,
        pn: ULong,
        aad: List<UByte>,
        ciphertextAndTag: MutableList<UByte>
    ): Result<List<UByte>> {
        val cryptoState = when (level) {
            borg.literbike.ccek.quic.EncryptionLevel.Initial -> initialState
            borg.literbike.ccek.quic.EncryptionLevel.Handshake -> handshakeState
            borg.literbike.ccek.quic.EncryptionLevel.OneRtt -> onerttState
        }

        return cryptoState?.decryptPayload(
            packetNumber = pn,
            aad = aad.toByteArray(),
            ciphertextAndTag = ciphertextAndTag.toMutableList().map { it.toByte() }
        )?.map { it.toUByte() }
            ?: Result.failure(QuicError.Protocol(ProtocolError.Crypto("No crypto state for level $level")))
    }

    override fun removeHeaderProtection(
        level: borg.literbike.ccek.quic.EncryptionLevel,
        sample: List<UByte>,
        first: UByteArray,
        pnBytes: UByteArray
    ): Result<Unit> {
        val cryptoState = when (level) {
            borg.literbike.ccek.quic.EncryptionLevel.Initial -> initialState
            borg.literbike.ccek.quic.EncryptionLevel.Handshake -> handshakeState
            borg.literbike.ccek.quic.EncryptionLevel.OneRtt -> onerttState
        }

        return cryptoState?.generateHeaderProtectionMask(sample.toByteArray())?.map { mask ->
            // Unmask first byte (low 4 bits for long header, low 5 bits for short header)
            val maskFirst = if (first[0].toInt() and 0x80 == 0x80) {
                mask[0].toInt() and 0x0F  // long header
            } else {
                mask[0].toInt() and 0x1F  // short header
            }
            first[0] = (first[0].toInt() xor maskFirst).toUByte()

            // Unmask packet number bytes
            val pnLen = (first[0].toInt() and 0x03) + 1
            for (i in 0 until pnLen) {
                pnBytes[i] = (pnBytes[i].toInt() xor mask[1 + i].toInt()).toUByte()
            }
        } ?: Result.failure(QuicError.Protocol(ProtocolError.Crypto("No crypto state for header protection")))
    }

    override fun clientDcid(): List<UByte>? = clientDcid.toList().map { it.toUByte() }
}

/** Convert List<UByte> to ByteArray */
private fun List<UByte>.toByteArray(): ByteArray = this.map { it.toByte() }.toByteArray()

/** Convert ByteArray to List<UByte> */
private fun ByteArray.toList(): List<UByte> = this.map { it.toUByte() }

/** Convert MutableList<Byte> to MutableList<UByte> */
private fun MutableList<Byte>.toMutableList(): MutableList<UByte> = this.map { it.toUByte() }.toMutableList()
