package borg.trikeshed.loom

import borg.trikeshed.lib.*
import kotlin.test.*

/** lease.rs LeaseReceipt against LoomVectors: the receipts Lease::receipt printed, and serde's JSON rules. */
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
}
