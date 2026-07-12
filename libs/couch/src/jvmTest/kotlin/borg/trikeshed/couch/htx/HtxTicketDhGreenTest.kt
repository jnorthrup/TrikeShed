package borg.trikeshed.couch.htx

import borg.trikeshed.couch.crypto.X25519PrivateKey
import borg.trikeshed.couch.crypto.X25519PublicKey
import borg.trikeshed.couch.crypto.x25519Dh
import kotlin.test.*

/**
 * GREEN test: Htx.verifyAccessTicket correctly uses clientPubKey via X25519 DH.
 *
 * This test verifies that:
 * 1. A ticket derived from the DH shared secret verifies correctly.
 * 2. Different clientPubKeys produce different verification results.
 * 3. A ticket derived from one clientPubKey does not verify with a different clientPubKey.
 */
class HtxTicketDhGreenTest {

    @Test
    fun `verifyAccessTicket uses clientPubKey via DH shared secret`() {
        // Simulate: two different clients with different keypairs
        val serverPrivRaw = ByteArray(32) { (it + 0x50).toByte() }
        val serverPriv = X25519PrivateKey(serverPrivRaw)

        val clientPubARaw = ByteArray(32) { 0x0A.toByte() }
        val clientPubA = X25519PublicKey(clientPubARaw)

        val clientPubBRaw = ByteArray(32) { 0x0B.toByte() }
        val clientPubB = X25519PublicKey(clientPubBRaw)

        val ticketKeyId = "ticket-001".encodeToByteArray()
        val hour = 1714226400L

        // Derive correct ticket for Client A
        val sharedSecretA = x25519Dh_inline(serverPrivRaw, clientPubARaw)
        val ticketA = deriveTicketFromSharedSecret(sharedSecretA, ticketKeyId, hour)

        // Derive correct ticket for Client B
        val sharedSecretB = x25519Dh_inline(serverPrivRaw, clientPubBRaw)
        val ticketB = deriveTicketFromSharedSecret(sharedSecretB, ticketKeyId, hour)

        // Verification for Client A should succeed with ticketA
        assertTrue(Htx.verifyAccessTicket(serverPrivRaw, clientPubARaw, ticketKeyId, ticketA, hour),
            "Ticket for Client A should verify correctly for Client A.")

        // Verification for Client B should succeed with ticketB
        assertTrue(Htx.verifyAccessTicket(serverPrivRaw, clientPubBRaw, ticketKeyId, ticketB, hour),
            "Ticket for Client B should verify correctly for Client B.")

        // CROSS-CLIENT VERIFICATION SHOULD FAIL
        assertFalse(Htx.verifyAccessTicket(serverPrivRaw, clientPubARaw, ticketKeyId, ticketB, hour),
            "Ticket for Client B should NOT verify for Client A.")

        assertFalse(Htx.verifyAccessTicket(serverPrivRaw, clientPubBRaw, ticketKeyId, ticketA, hour),
            "Ticket for Client A should NOT verify for Client B.")
    }

    @Test
    fun `ticket derived from raw serverPrivKey should no longer verify`() {
        val serverPrivRaw = ByteArray(32) { (it + 0x50).toByte() }
        val clientPubRaw = ByteArray(32) { 0x42.toByte() }
        val ticketKeyId = "ticket-001".encodeToByteArray()
        val hour = 1714226400L

        // Derive a ticket from raw serverPrivKey (the OLD broken path)
        val legacyTicket = deriveTicketFromSharedSecret(serverPrivRaw, ticketKeyId, hour)

        // This should now fail because the code expects a DH shared secret
        val result = Htx.verifyAccessTicket(serverPrivRaw, clientPubRaw, ticketKeyId, legacyTicket, hour)
        assertFalse(result, "Legacy tickets derived from raw private keys should no longer verify.")
    }

    // ── helpers ───────────────────────────────────────────────────────

    private fun deriveTicketFromSharedSecret(
        sharedSecret: ByteArray,
        ticketKeyId: ByteArray,
        hour: Long,
    ): ByteArray {
        val prk = hmacSha256_inline(sharedSecret, ticketKeyId)
        val hourBytes = ByteArray(8)
        for (i in 0..7) {
            hourBytes[7 - i] = (hour shr (i * 8)).toByte()
        }
        val info = Htx.TICKET_V1_CONTEXT.encodeToByteArray() + hourBytes + byteArrayOf(0x01)
        return hmacSha256_inline(prk, info).copyOf(Htx.TICKET_LEN)
    }
}

/**
 * Inline HMAC-SHA256 for the test helper.
 */
internal fun hmacSha256_inline(key: ByteArray, data: ByteArray): ByteArray {
    val mac = javax.crypto.Mac.getInstance("HmacSHA256")
    mac.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(data)
}

/**
 * Mock X25519 DH for the test helper, mirroring the expected behavior.
 * In a real environment, this would use the platform's X25519 implementation.
 * For testing purposes, we use a simple deterministic transformation if we can't easily call the actual DH.
 * However, since we want to verify the logic, we should ideally use the same DH logic.
 *
 * Looking at CryptoPrimitivesJvm.kt might help.
 */
internal fun x25519Dh_inline(ours: ByteArray, theirs: ByteArray): ByteArray {
    // In jvmMain, this uses java.security.KeyFactory and javax.crypto.KeyAgreement.
    // For the purpose of this test, we can try to use the same logic if possible,
    // or just acknowledge that the Htx.kt will call the 'actual' x25519Dh.
    // To make the test PASS, we need to use the SAME DH as the 'actual' implementation.

    // Let's see if we can just call the actual one if it's available in the test classpath.
    return borg.trikeshed.couch.crypto.x25519Dh(
        borg.trikeshed.couch.crypto.X25519PrivateKey(ours),
        borg.trikeshed.couch.crypto.X25519PublicKey(theirs)
    )
}
