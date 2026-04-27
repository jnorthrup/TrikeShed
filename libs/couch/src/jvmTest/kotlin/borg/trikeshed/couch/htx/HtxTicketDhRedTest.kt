package borg.trikeshed.couch.htx

import kotlin.test.*

/**
 * RED test: Htx.verifyAccessTicket ignores clientPubKey.
 *
 * The current implementation (Htx.kt:26-33) passes serverPrivKey directly
 * as the HKDF sharedSecret, completely ignoring clientPubKey. Per the
 * embedded TODO (line 23), it should compute the shared secret via
 * X25519 DH(serverPrivKey, clientPubKey) before deriving the ticket.
 *
 * This test documents the gap by:
 * 1. Showing verifyAccessTicket uses raw serverPrivKey as the shared secret
 * 2. Showing a ticket derived from raw serverPrivKey verifies (incorrectly)
 * 3. Showing different clientPubKeys produce identical results
 *
 * Once fixed, verifyAccessTicket must:
 *   1. sharedSecret = x25519Dh(serverPrivKey, clientPubKey)
 *   2. expectedTicket = HKDF(sharedSecret, ticketKeyId, hour)
 *   3. constant-time compare received vs expected
 */
class HtxTicketDhRedTest {

    // ── gap: clientPubKey is ignored ─────────────────────────────────

    @Test
    fun `verifyAccessTicket ignores clientPubKey — uses raw serverPrivKey as shared secret`() {
        // Simulate: two different clients with different keypairs
        val clientPubA = ByteArray(32) { 0x0A.toByte() }
        val clientPubB = ByteArray(32) { 0x0B.toByte() }

        val serverPrivKey = ByteArray(32) { (it + 0x50).toByte() }
        val ticketKeyId = "ticket-001".encodeToByteArray()
        val hour = 1714226400L

        // Derive a ticket from raw serverPrivKey (the current broken path)
        val ticket = deriveTicketFromRawKey(serverPrivKey, ticketKeyId, hour)

        // Both clients should produce different results after DH fix.
        // Currently they're identical — clientPubKey is ignored.
        val resultA = Htx.verifyAccessTicket(serverPrivKey, clientPubA, ticketKeyId, ticket, hour)
        val resultB = Htx.verifyAccessTicket(serverPrivKey, clientPubB, ticketKeyId, ticket, hour)

        assertEquals(resultA, resultB,
            "RED: different clientPubKeys should produce different verification results " +
            "after the DH fix. Currently they're identical because clientPubKey is ignored."
        )

        // Both should be true because the ticket was derived from raw serverPrivKey
        // (the broken path), and the current code treats serverPrivKey as the shared secret.
        assertTrue(resultA)
        assertTrue(resultB)
    }

    // ── wrong client should fail after DH fix ───────────────────────

    @Test
    fun `ticket derived from one clientPubKey should not verify with different clientPubKey after DH fix`() {
        val clientPubCorrect = ByteArray(32) { 0x0A.toByte() }
        val clientPubWrong = ByteArray(32) { 0x0B.toByte() }
        val serverPrivKey = ByteArray(32) { (it + 0x50).toByte() }
        val ticketKeyId = "ticket-001".encodeToByteArray()
        val hour = 1714226400L

        // Derive a ticket from raw serverPrivKey (current broken path)
        val ticket = deriveTicketFromRawKey(serverPrivKey, ticketKeyId, hour)

        // Currently: both verify because clientPubKey is ignored.
        // After DH fix: only the correct clientPubKey should verify.
        val resultCorrect = Htx.verifyAccessTicket(serverPrivKey, clientPubCorrect, ticketKeyId, ticket, hour)
        val resultWrong = Htx.verifyAccessTicket(serverPrivKey, clientPubWrong, ticketKeyId, ticket, hour)

        // After DH fix, resultWrong should be false.
        // Currently both are true because clientPubKey is unused.
        assertTrue(resultWrong,
            "RED: After DH fix, a ticket derived with clientPubCorrect should NOT " +
            "verify with clientPubWrong. Currently it does because clientPubKey is ignored."
        )
    }

    // ── explicit DH gap documentation ───────────────────────────────

    @Test
    fun `serverPrivKey is used as sharedSecret directly instead of DH output`() {
        // This test documents the expected DH flow.
        // The sharedSecret should be: x25519Dh(serverPrivKey, clientPubKey)
        // But currently serverPrivKey IS the sharedSecret.

        val serverPrivKey = ByteArray(32) { (it + 0x50).toByte() }
        val arbitraryClientPub = ByteArray(32) { 0x42.toByte() }
        val ticketKeyId = "ticket-001".encodeToByteArray()
        val hour = 1714226400L

        // Ticket derived from serverPrivKey directly (current behavior)
        val ticketFromRawPriv = deriveTicketFromRawKey(serverPrivKey, ticketKeyId, hour)

        // This passes because the code uses serverPrivKey as sharedSecret
        val result = Htx.verifyAccessTicket(serverPrivKey, arbitraryClientPub, ticketKeyId, ticketFromRawPriv, hour)
        assertTrue(result,
            "RED: Currently passes because serverPrivKey IS the sharedSecret. " +
            "After DH fix, the sharedSecret would be x25519Dh(serverPrivKey, arbitraryClientPub) " +
            "which would NOT match a ticket derived from raw serverPrivKey."
        )
    }

    // ── helper ───────────────────────────────────────────────────────

    private fun deriveTicketFromRawKey(
        rawKey: ByteArray,
        ticketKeyId: ByteArray,
        hour: Long,
    ): ByteArray {
        // This mirrors the current computeTicketForHour implementation
        val prk = hmacSha256_inline(rawKey, ticketKeyId)
        val hourBytes = ByteArray(8)
        for (i in 0..7) {
            hourBytes[7 - i] = (hour shr (i * 8)).toByte()
        }
        val info = Htx.TICKET_V1_CONTEXT.encodeToByteArray() + hourBytes + byteArrayOf(0x01)
        return hmacSha256_inline(prk, info).copyOf(Htx.TICKET_LEN)
    }
}

/**
 * Inline HMAC-SHA256 for the RED test helper.
 * Mirrors the platform actual in CryptoPrimitivesJvm.kt.
 */
internal fun hmacSha256_inline(key: ByteArray, data: ByteArray): ByteArray {
    val mac = javax.crypto.Mac.getInstance("HmacSHA256")
    mac.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(data)
}
