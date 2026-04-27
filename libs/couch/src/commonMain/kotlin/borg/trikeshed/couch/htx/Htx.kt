package borg.trikeshed.couch.htx

/**
 * HTX Protocol Implementation ported from literbike.
 * Provides ticket verification for access control.
 */

/** Platform-specific HMAC-SHA256. Implemented in jvmMain via javax.crypto.Mac. */
internal expect fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray

class Htx {
    companion object {
        const val TICKET_V1_CONTEXT = "liteyear-ticket-v1"
        const val TICKET_LEN = 32

        /**
         * Verifies an HTX access ticket with constant-time comparison.
         *
         * Uses bitwise XOR accumulator — no short-circuit, no early exit.
         * Each byte is compared independently, the diff accumulator holds
         * the OR of all per-byte XOR results.  Final check `diff == 0`.
         *
         * TODO: X25519 DH between serverPrivKey and clientPubKey to derive
         *       the shared secret, rather than using serverPrivKey directly.
         */
        fun verifyAccessTicket(
            serverPrivKey: ByteArray,
            clientPubKey: ByteArray,
            ticketKeyId: ByteArray,
            receivedTicket: ByteArray,
            currentHour: Long
        ): Boolean {
            val expectedTicket = computeTicketForHour(serverPrivKey, ticketKeyId, currentHour)
            if (receivedTicket.size != expectedTicket.size) return false
            var diff = 0
            for (i in receivedTicket.indices) {
                diff = diff or (receivedTicket[i].toInt() xor expectedTicket[i].toInt())
            }
            return diff == 0
        }

        /**
         * HKDF-SHA256 ticket derivation for a given hour.
         * PRK = HMAC-SHA256(salt=sharedSecret, ikm=ticketKeyId)
         * T   = HMAC-SHA256(key=PRK, data=TICKET_V1_CONTEXT || hour_be || 0x01)
         * Result is T truncated to TICKET_LEN (32 bytes).
         */
       fun computeTicketForHour(
            sharedSecret: ByteArray,
            ticketKeyId: ByteArray,
            hour: Long
        ): ByteArray {
            // HKDF-Extract: PRK = HMAC-SHA256(salt=sharedSecret, IKM=ticketKeyId)
            val prk = hmacSha256(sharedSecret, ticketKeyId)

            // HKDF-Expand: T(1) = HMAC-SHA256(PRK, info || 0x01)
            val hourBytes = ByteArray(8)
            for (i in 0..7) {
                hourBytes[7 - i] = (hour shr (i * 8)).toByte()
            }
            val info = TICKET_V1_CONTEXT.encodeToByteArray() + hourBytes
            val expandInput = info + byteArrayOf(0x01)
            return hmacSha256(prk, expandInput).copyOf(TICKET_LEN)
        }
    }
}
