package borg.literbike.ccek.quic

// ============================================================================
// QUIC Crypto Provider Interface -- ported from quic_crypto.rs
// ============================================================================

/** Encryption levels per RFC 9001 */
enum class EncryptionLevel {
    Initial,
    Handshake,
    OneRtt
}

/** Pending crypto write at a given encryption level */
data class CryptoWrite(
    val level: EncryptionLevel,
    val data: List<UByte>
)

/** Handshake phase state machine */
enum class HandshakePhase {
    Initial,
    Handshaking,
    OneRtt,
    Closed
}

/** Context for inbound header protection removal (RFC 9001 Section 5.4) */
data class InboundHeaderProtectionContext(
    val expectedPacketNumber: ULong,
    val truncatedPacketNumber: ULong,
    val packetNumberLen: Int
)

/** Context for outbound header protection application (RFC 9001 Section 5.4) */
data class OutboundHeaderProtectionContext(
    val packetNumber: ULong,
    val packetNumberLen: Int
)

/** Result of processing a CRYPTO frame */
enum class CryptoFrameDisposition {
    AckOnly,
    ProgressedHandshake
}

/**
 * QUIC Crypto Provider trait -- ported from Rust QuicCryptoProvider trait.
 * Implementations provide header protection, AEAD encryption/decryption,
 * and TLS handshake state management.
 */
interface QuicCryptoProvider {

    /** Apply inbound header protection removal (RFC 9001 Section 5.4) */
    fun onInboundHeader(header: QuicHeader, ctx: InboundHeaderProtectionContext): Result<Unit> =
        Result.success(Unit)

    /** Apply outbound header protection (RFC 9001 Section 5.4) */
    fun onOutboundHeader(header: QuicHeader, ctx: OutboundHeaderProtectionContext): Result<Unit> =
        Result.success(Unit)

    /** Process a CRYPTO frame, returning disposition */
    fun onCryptoFrame(frame: CryptoFrame, level: EncryptionLevel, state: QuicConnectionState): Result<CryptoFrameDisposition> =
        Result.success(CryptoFrameDisposition.AckOnly)

    /** Current handshake phase */
    fun handshakePhase(): HandshakePhase = HandshakePhase.Initial

    /** Whether header protection is ready for use */
    fun headerProtectionReady(): Boolean = false

    /** Whether TLS handshake is complete (1-RTT keys available) */
    fun handshakeComplete(): Boolean = handshakePhase() == HandshakePhase.OneRtt

    /** Drain pending crypto writes for transmission */
    fun drainCryptoWrites(): List<CryptoWrite> = emptyList()

    /** Encrypt a packet payload with AEAD */
    fun encryptPacket(level: EncryptionLevel, pn: ULong, header: List<UByte>, payload: MutableList<UByte>): Result<Unit> =
        Result.success(Unit)

    /** Apply header protection to outbound packet */
    fun applyHeaderProtection(level: EncryptionLevel, sample: List<UByte>, first: UByteArray, pnBytes: UByteArray): Result<Unit> =
        Result.success(Unit)

    /** Remove header protection from inbound packet (modifies first and pnBytes in-place) */
    fun removeHeaderProtection(level: EncryptionLevel, sample: List<UByte>, first: UByteArray, pnBytes: UByteArray): Result<Unit> =
        Result.failure(QuicError.Protocol(ProtocolError.Crypto("removeHeaderProtection not supported")))

    /** Decrypt inbound packet payload (AEAD). Returns plaintext bytes. */
    fun decryptPacket(level: EncryptionLevel, pn: ULong, aad: List<UByte>, ciphertextAndTag: MutableList<UByte>): Result<List<UByte>> =
        Result.failure(QuicError.Protocol(ProtocolError.Crypto("decryptPacket not supported")))

    /** Return the original client DCID used for key derivation (server only) */
    fun clientDcid(): List<UByte>? = null
}

/** No-op crypto provider -- pass-through for unencrypted QUIC */
object NoopQuicCryptoProvider : QuicCryptoProvider
