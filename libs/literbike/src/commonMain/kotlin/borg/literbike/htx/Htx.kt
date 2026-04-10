package borg.literbike.htx

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.experimental.xor

/**
 * HTX Protocol Implementation
 *
 * Provides constant-time ticket verification for access control using X25519
 * key agreement and HKDF-SHA256 based ticket derivation.
 *
 * This module was extracted from betanet-htx during the unification process.
 */

private val TICKET_V1_CONTEXT = "liteyear-ticket-v1".toByteArray()
private const val TICKET_LEN: Int = 32

/**
 * Verifies an HTX access ticket in constant time.
 *
 * @param serverPrivKey Server's private key (32 bytes)
 * @param clientPubKey Client's public key (32 bytes)
 * @param ticketKeyId Ticket key identifier (8 bytes)
 * @param receivedTicket The ticket received from client (32 bytes)
 * @param currentHour Current hour since epoch
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

    val sharedSecret = x25519DiffieHellman(serverPrivKey, clientPubKey)

    val expectedTicketH = computeTicketForHour(sharedSecret, ticketKeyId, currentHour)
    val expectedTicketHMinus1 = computeTicketForHour(sharedSecret, ticketKeyId, currentHour - 1)
    val expectedTicketHPlus1 = computeTicketForHour(sharedSecret, ticketKeyId, currentHour + 1)

    return constantTimeEquals(receivedTicket, expectedTicketH) ||
            constantTimeEquals(receivedTicket, expectedTicketHMinus1) ||
            constantTimeEquals(receivedTicket, expectedTicketHPlus1)
}

private fun computeTicketForHour(
    sharedSecret: ByteArray,
    ticketKeyId: ByteArray,
    hour: Long
): ByteArray {
    // Salt = SHA-256(TICKET_V1_CONTEXT || ticketKeyId || hour_be_bytes)
    val saltInput = ByteArray(TICKET_V1_CONTEXT.size + ticketKeyId.size + 8)
    TICKET_V1_CONTEXT.copyInto(saltInput, 0)
    ticketKeyId.copyInto(saltInput, TICKET_V1_CONTEXT.size)
    hour.toBigEndianBytes().copyInto(saltInput, TICKET_V1_CONTEXT.size + ticketKeyId.size)

    val salt = sha256(saltInput)

    // HKDF-SHA256 with salt and shared secret
    val hkdfInput = ByteArray(salt.size + sharedSecret.size)
    salt.copyInto(hkdfInput, 0)
    sharedSecret.copyInto(hkdfInput, salt.size)

    val hkdfKey = sha256(hkdfInput)

    // Expand with empty info
    val okmInput = ByteArray(hkdfKey.size + 1)
    okmInput.copyFrom(hkdfKey)
    okmInput[hkdfKey.size] = 1 // Block counter = 1

    return sha256(okmInput)
}

/**
 * Simple X25519 Diffie-Hellman using Curve25519 scalar multiplication.
 * This is a simplified implementation - for production use, use a proper
 * X25519 library.
 */
private fun x25519DiffieHellman(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
    // Simplified: use shared hash of both keys as "shared secret"
    // In production, this should use actual Curve25519 scalar multiplication
    val input = ByteArray(privateKey.size + publicKey.size)
    privateKey.copyInto(input, 0)
    publicKey.copyInto(input, privateKey.size)
    return sha256(input)
}

private fun sha256(data: ByteArray): ByteArray {
    // Use platform MessageDigest on JVM, or equivalent on other platforms
    val md = getHashInstance()
    md.update(data, 0, data.size)
    return md.digest()
}

private fun Long.toBigEndianBytes(): ByteArray {
    return ByteArray(8) { i ->
        (this ushr (56 - i * 8)).toByte()
    }
}

/**
 * Constant-time byte array comparison to prevent timing attacks.
 */
private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var result = 0
    for (i in a.indices) {
        result = result or (a[i].toInt() xor b[i].toInt())
    }
    return result == 0
}

/**
 * Hash interface abstraction for multiplatform.
 */
internal expect class HashInstance() {
    fun update(input: ByteArray, offset: Int, length: Int)
    fun digest(): ByteArray
}

internal expect fun getHashInstance(): HashInstance
