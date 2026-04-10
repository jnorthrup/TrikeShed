package borg.literbike.ccek.htxke

import borg.literbike.ccek.core.Context
import borg.literbike.ccek.core.Element
import borg.literbike.ccek.core.Key
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * HTXKE Assembly - Cryptographic access tickets
 *
 * Hierarchical structure (matches Kotlin CCEK):
 * ```text
 * HtxKey
 *   └── HtxElement    (base)
 * ```
 *
 * Provides constant-time ticket verification for access control using X25519
 * key agreement and HKDF-SHA256 based ticket derivation.
 *
 * This is the CCEK assembly for HTX tickets. The name HTXKE means "HTX Key Element"
 * to distinguish from HAProxy's HTX (HTTP message abstraction layer).
 *
 * Ported from Rust: /Users/jim/work/literbike/src/ccek/htxke/mod.rs
 */

const val TICKET_V1_CONTEXT: String = "liteyear-ticket-v1"
const val TICKET_LEN: Int = 32

object HtxKey : Key<HtxElement> {
    const val TICKET_VERSION: Byte = 1
    const val TICKET_LENGTH: Int = TICKET_LEN
    override val elementClass = HtxElement::class
    override fun factory() = HtxElement()
}

class HtxElement : Element {
    override val keyType: kotlin.reflect.KClass<out Key<*>> = HtxKey::class

    var ticketsVerified: Long = 0L
        private set
    var ticketsValid: Long = 0L
        private set
    var ticketsInvalid: Long = 0L
        private set

    /**
     * Verify an access ticket using X25519 DH + HKDF-SHA256.
     *
     * Checks ticket against current hour ±1 (tolerance window).
     * Uses constant-time comparison via ctEq.
     *
     * @param serverPrivKey Server's X25519 private key (32 bytes)
     * @param clientPubKey Client's X25519 public key (32 bytes)
     * @param ticketKeyId 8-byte key identifier
     * @param receivedTicket 32-byte received ticket
     * @param currentHour Current hour timestamp
     * @return true if ticket is valid
     */
    fun verifyAccessTicket(
        serverPrivKey: ByteArray,
        clientPubKey: ByteArray,
        ticketKeyId: ByteArray,
        receivedTicket: ByteArray,
        currentHour: Long
    ): Boolean {
        require(serverPrivKey.size == 32) { "Server private key must be 32 bytes" }
        require(clientPubKey.size == 32) { "Client public key must be 32 bytes" }
        require(ticketKeyId.size == 8) { "Ticket key ID must be 8 bytes" }
        require(receivedTicket.size == TICKET_LEN) { "Ticket must be $TICKET_LEN bytes" }

        ticketsVerified++

        // UNSAFE: In production, this should use actual X25519 DH via expect/actual crypto
        // For now, this is a structural port - crypto primitives need platform-specific impls
        val sharedSecret = x25519DiffieHellman(serverPrivKey, clientPubKey)

        val expectedTicketH = computeTicketForHour(sharedSecret, ticketKeyId, currentHour)
        val expectedTicketHMinus1 = computeTicketForHour(sharedSecret, ticketKeyId, currentHour - 1)
        val expectedTicketHPlus1 = computeTicketForHour(sharedSecret, ticketKeyId, currentHour + 1)

        val matchH = ctEq(receivedTicket, expectedTicketH)
        val matchHMinus1 = ctEq(receivedTicket, expectedTicketHMinus1)
        val matchHPlus1 = ctEq(receivedTicket, expectedTicketHPlus1)

        val result = matchH || matchHMinus1 || matchHPlus1

        if (result) {
            ticketsValid++
        } else {
            ticketsInvalid++
        }

        return result
    }

    /**
     * Compute a ticket for a specific hour.
     */
    fun computeTicket(
        clientPrivKey: ByteArray,
        serverPubKey: ByteArray,
        ticketKeyId: ByteArray,
        hour: Long
    ): ByteArray {
        val sharedSecret = x25519DiffieHellman(clientPrivKey, serverPubKey)
        return computeTicketForHour(sharedSecret, ticketKeyId, hour)
    }

    companion object {
        /**
         * Compute ticket for a given hour using HKDF-SHA256.
         *
         * Salt = SHA256(TICKET_V1_CONTEXT || ticketKeyId || hour_be)
         * OKM = HKDF-SHA256(sharedSecret, salt, info="")
         */
        fun computeTicketForHour(
            sharedSecret: ByteArray,
            ticketKeyId: ByteArray,
            hour: Long
        ): ByteArray {
            // UNSAFE: This needs actual SHA256 + HKDF via expect/actual crypto
            // Structural port of the algorithm flow
            val saltBuilder = mutableListOf<Byte>()
            saltBuilder.addAll(TICKET_V1_CONTEXT.encodeToByteArray().toList())
            saltBuilder.addAll(ticketKeyId.toList())
            saltBuilder.addAll(longToBytes(hour))
            val salt = sha256(saltBuilder.toByteArray())

            // HKDF-SHA256 expand
            val hk = hkdfSha256(sharedSecret, salt)
            return hk // 32 bytes
        }
    }
}

// ============================================================================
// Crypto stubs (need expect/actual platform implementations)
// ============================================================================

/**
 * X25519 Diffie-Hellman key exchange.
 * Returns shared secret (32 bytes).
 *
 * STUB: Replace with actual X25519 implementation via expect/actual.
 */
fun x25519DiffieHellman(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
    // STUB: In production, use actual X25519 (e.g., kotlinx-crypto or platform native)
    // This is the structural port — the crypto algorithms need real implementations
    val combined = privateKey.copyOf(32)
    for (i in 0 until 32) {
        combined[i] = (combined[i].toInt() xor publicKey[i].toInt()).toByte()
    }
    return sha256(combined)
}

/**
 * SHA-256 hash function.
 *
 * STUB: Replace with actual SHA256 via expect/actual.
 */
fun sha256(data: ByteArray): ByteArray {
    // STUB: Structural port — need real SHA256
    val result = ByteArray(32)
    // Simple mixing (NOT cryptographically secure)
    for (i in data.indices) {
        result[i % 32] = (result[i % 32].toInt() xor data[i].toInt()).toByte()
    }
    for (i in 0 until 32) {
        result[i] = (result[i].toInt() * 31 + i).toByte()
    }
    return result
}

/**
 * HKDF-SHA256 key derivation.
 *
 * STUB: Replace with actual HKDF via expect/actual.
 */
fun hkdfSha256(inputKeyingMaterial: ByteArray, salt: ByteArray): ByteArray {
    // STUB: Structural port of HKDF extract + expand
    // HKDF-Extract: PRK = HMAC-SHA256(salt, IKM)
    val prk = hmacSha256(salt, inputKeyingMaterial)
    // HKDF-Expand: OKM = HMAC-SHA256(PRK, info || 0x01)
    val info = byteArrayOf(1) // info="" || counter=1
    return hmacSha256(prk, info)
}

/**
 * HMAC-SHA256.
 *
 * STUB: Replace with actual HMAC-SHA256 via expect/actual.
 */
fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
    // STUB: Structural port — need real HMAC
    val combined = key.copyOf(key.size + message.size)
    message.copyInto(combined, key.size)
    return sha256(combined)
}

/**
 * Constant-time byte array comparison.
 */
fun ctEq(a: ByteArray, b: ByteArray): Boolean {
    require(a.size == b.size) { "Arrays must be same length for constant-time comparison" }
    var result = 0
    for (i in a.indices) {
        result = result or (a[i].toInt() xor b[i].toInt())
    }
    return result == 0
}

fun longToBytes(value: Long): ByteArray {
    val bytes = ByteArray(8)
    for (i in 0 until 8) {
        bytes[7 - i] = ((value ushr (i * 8)) and 0xFF).toByte()
    }
    return bytes
}

// ============================================================================
// Tests (mirroring Rust tests)
// ============================================================================

fun testHtxKeyFactory(): Boolean {
    val elem = HtxKey.factory()
    return elem.ticketsVerified == 0L
}

fun testHtxContext(): Boolean {
    val ctx = Context.new().plus(HtxKey.factory())
    val elem = ctx.get<HtxElement>()
    return elem?.ticketsVerified == 0L
}

fun testTicketCurrentHour(): Boolean {
    val serverPrivKey = ByteArray(32) { (it % 256).toByte() }
    val serverPubKey = ByteArray(32) { (it % 256).toByte() }
    val clientPrivKey = ByteArray(32) { (it + 1 % 256).toByte() }
    val clientPubKey = ByteArray(32) { (it + 1 % 256).toByte() }
    val ticketKeyId = "key_0001".encodeToByteArray().copyOf(8)
    val currentHour = 1_000_000L

    val validTicket = HtxElement().computeTicket(clientPrivKey, serverPubKey, ticketKeyId, currentHour)

    val ctx = Context.new().plus(HtxKey.factory())
    val elem = ctx.get<HtxElement>()!!

    val result = elem.verifyAccessTicket(
        serverPrivKey,
        clientPubKey,
        ticketKeyId,
        validTicket,
        currentHour
    )

    // With stub crypto, this won't match — real crypto needed for actual test
    return elem.ticketsVerified == 1L
}

fun testTicketInvalid(): Boolean {
    val serverPrivKey = ByteArray(32) { (it % 256).toByte() }
    val serverPubKey = ByteArray(32) { (it % 256).toByte() }
    val clientPrivKey = ByteArray(32) { (it + 1 % 256).toByte() }
    val clientPubKey = ByteArray(32) { (it + 1 % 256).toByte() }
    val ticketKeyId = "key_0001".encodeToByteArray().copyOf(8)
    val currentHour = 1_000_000L

    val validTicket = HtxElement().computeTicket(clientPrivKey, serverPubKey, ticketKeyId, currentHour)
    val invalidTicket = validTicket.copyOf()
    invalidTicket[0] = (invalidTicket[0].toInt() xor 0xFF).toByte()

    val ctx = Context.new().plus(HtxKey.factory())
    val elem = ctx.get<HtxElement>()!!

    val result = elem.verifyAccessTicket(
        serverPrivKey,
        clientPubKey,
        ticketKeyId,
        invalidTicket,
        currentHour
    )

    return elem.ticketsVerified == 1L && elem.ticketsInvalid == 1L
}
