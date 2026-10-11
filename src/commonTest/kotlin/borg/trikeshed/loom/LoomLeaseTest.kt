package borg.trikeshed.loom

import borg.trikeshed.lib.*
import kotlin.test.*

/** lease.rs against LoomVectors and LeaseVectors: what Lease, LeaseReceipt, parse_duration and selectable did in Rust, and serde's JSON rules. */
class LoomLeaseTest {
    fun serde(r: LeaseReceipt): String =
        "{\"format\":${json(r.format)},\"provider\":${json(r.provider.name.lowercase())},\"purpose\":${json(r.purpose.name.lowercase())}," +
            "\"enforcement\":${json(r.enforcement.name.lowercase())},\"scope\":${json(r.scope)},\"issued_at\":${r.issued_at}," +
            "\"not_after\":${r.not_after},\"key_id\":${json(r.key_id)}}"

    @Test
    fun receiptsOfEachProviderAgree() {
        for ((provider, receiptJson) in LoomVectors.leaseReceipt.view) {
            val receipt = LeaseReceipt.from_json(receiptJson.encodeToByteArray())
            assertEquals(receiptJson, serde(receipt), provider)
            assertEquals(receiptJson, receipt.to_json().decodeToString(), provider)
            assertEquals(RECEIPT_FORMAT, receipt.format, provider)
            assertEquals(provider, receipt.provider.name.lowercase())
            assertEquals(receipt.provider.purpose(), receipt.purpose, provider)
            assertEquals(receipt.provider.enforcement(), receipt.enforcement, provider)
        }
    }

    @Test
    fun receiptJsonAgreesWithSerde() {
        for ((name, case) in LoomVectors.leaseReceiptJson.view) {
            val (input, expected) = case
            val result = try {
                "ok:" + serde(LeaseReceipt.from_json(input.encodeToByteArray()))
            } catch (e: IllegalStateException) {
                "error"
            }
            assertEquals(expected, result, name)
        }
    }

    fun outcome(block: () -> String): String = try {
        block()
    } catch (refused: IllegalStateException) {
        "error:" + refused.message
    }

    @Test
    fun leaseJsonAgreesWithSerde() {
        for ((name, case) in LeaseVectors.leaseJson.view) {
            val (input, expected) = case
            val result = outcome {
                val lease = Lease.from_json(input.encodeToByteArray())
                "ok:" + lease.to_json().decodeToString() + " " + lease.receipt().to_json().decodeToString()
            }
            assertEquals(expected, result, name)
        }
    }

    @Test
    fun checkUsableAgrees() {
        val lease = Lease.from_json(LeaseVectors.leaseJson.view.first { it.a == "google" }.b.a.encodeToByteArray())
        for ((now, expected) in LeaseVectors.checkUsable.view) {
            assertEquals(expected, outcome { lease.check_usable(now); "ok" }, "$now")
        }
    }

    @Test
    fun durationsAndWindowsAgree() {
        for ((input, expected) in LeaseVectors.parseDuration.view) {
            assertEquals(expected, outcome { "ok:" + parse_duration(input) }, input)
        }
        for ((input, expected) in LeaseVectors.selectable.view) {
            val (provider, secs, extended) = input.split(' ')
            assertEquals(expected, outcome { "ok:" + selectable(Provider.parse(provider), secs.toULong(), extended.toBoolean()) }, input)
        }
    }
}
