package borg.trikeshed.loom

import borg.trikeshed.ipns.IpnsCrypto
import borg.trikeshed.lib.*
import kotlin.test.*

/** ledger.rs LedgerVersion against LoomVectors. */
abstract class LoomLedgerTest(val crypto: IpnsCrypto) {
    val key = LoomVectors.SEED_AUTHORITY.hexToByteArray()
    val authority = members()[0]
    val t = LoomVectors.T

    fun v1() = LedgerVersion.sign("authority", "customer-books", 1uL, ByteArray(32), t, "cumulative ledger state v1".encodeToByteArray(), key, crypto)

    @Test
    fun versionsChainToTheCapturedBytes() {
        val v1 = v1()
        assertEquals(LoomVectors.LEDGER_V1, v1.to_bytes().toHexString())
        assertEquals(LoomVectors.LEDGER_V1_DIGEST, v1.digest().toHexString())
        assertEquals(LoomVectors.LEDGER_V1_EPOCH_START, v1.epoch_start())
        assertEquals(LoomVectors.LEDGER_V1_WINDOW_OPEN, v1.window_open())
        val v2 = v1.next(t + 900uL, "cumulative ledger state v2".encodeToByteArray(), key, crypto)
        assertEquals(LoomVectors.LEDGER_V2, v2.to_bytes().toHexString())
        assertEquals(LoomVectors.LEDGER_V2_DIGEST, v2.digest().toHexString())
        assertEquals(LoomVectors.LEDGER_V2_EPOCH_START, v2.epoch_start())
        assertEquals(LoomVectors.LEDGER_V2_OBJECT_NAME, v2.object_name())
        for (captured in s_[LoomVectors.LEDGER_V1, LoomVectors.LEDGER_V2].view) {
            val version = LedgerVersion.from_bytes(captured.hexToByteArray())
            assertEquals(captured, version.to_bytes().toHexString())
            version.verify(authority, crypto)
        }
        assertEquals(LoomVectors.LEDGER_EPOCH_SECS, LEDGER_EPOCH_SECS)
        assertEquals(LoomVectors.LEDGER_DEADLINE_SECS, LEDGER_DEADLINE_SECS)
    }

    @Test
    fun ledgerRejectionsAgree() {
        val body = "state".encodeToByteArray()
        val edge = Long.MAX_VALUE.toULong() - LEDGER_DEADLINE_SECS
        val ones = ByteArray(32) { 1 }
        fun sign(version: ULong, previous: ByteArray, issued_at: ULong, body: ByteArray, stream: String = "s") =
            LedgerVersion.sign("authority", stream, version, previous, issued_at, body, key, crypto)
        val tampered = LoomVectors.LEDGER_V1.hexToByteArray().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        val cases: Map<String, () -> Unit> = mapOf(
            "first_with_previous" to { sign(1uL, ones, t, body) },
            "later_without_previous" to { sign(2uL, ByteArray(32), t, body) },
            "version_0" to { sign(0uL, ByteArray(32), t, body) },
            "version_range" to { sign(1uL shl 63, ones, t, body) },
            "issued_0" to { sign(1uL, ByteArray(32), 0uL, body) },
            "issued_edge" to { sign(1uL, ByteArray(32), edge, body) },
            "issued_over_edge" to { sign(1uL, ByteArray(32), edge + 1uL, body) },
            "body_empty" to { sign(1uL, ByteArray(32), t, ByteArray(0)) },
            "body_max" to { sign(1uL, ByteArray(32), t, ByteArray(MAX_LEDGER_BODY)) },
            "body_over" to { sign(1uL, ByteArray(32), t, ByteArray(MAX_LEDGER_BODY + 1)) },
            "bad_stream" to { sign(1uL, ByteArray(32), t, body, stream = "s/1") },
            "wrong_authority" to { v1().verify(members()[1], crypto) },
            "tampered" to { LedgerVersion.from_bytes(tampered).verify(authority, crypto) },
        )
        for ((name, expected) in LoomVectors.ledgerErrors.view) assertEquals(expected, outcome(cases.getValue(name)), name)
    }
}
