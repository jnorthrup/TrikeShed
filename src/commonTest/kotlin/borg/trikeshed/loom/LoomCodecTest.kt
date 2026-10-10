package borg.trikeshed.loom

import borg.trikeshed.collections.associative.*
import borg.trikeshed.job.sha256
import borg.trikeshed.lib.*
import kotlin.test.*

/** TrikeShed Item/Cbor against confix-rs item.rs, and [decode] against loom-mesh codec.rs (LoomVectors). */
class LoomCodecTest {
    @Test
    fun itemsEncodeToTheConfixRsBytes() {
        for ((name, case) in LoomVectors.item.view) {
            val (item, hex) = case
            assertEquals(hex, Cbor.encode(item).toHexString(), name)
            assertEquals(hex, Cbor.encode(Cbor.decode(hex.hexToByteArray())).toHexString(), name)
        }
    }

    @Test
    fun itemDecodeAgreesWithConfixRs() {
        for ((name, case) in LoomVectors.itemDecode.view) {
            val (input, expected) = case
            val result = try {
                Cbor.encode(Cbor.decode(input.hexToByteArray())).toHexString()
            } catch (e: Exception) {
                "error"
            }
            assertEquals(expected, result, name)
        }
    }

    @Test
    fun decodeBoundsAgreeWithLoomMesh() {
        for ((name, case) in LoomVectors.codec.view) {
            val (input, expected) = case
            val bytes = if (input.startsWith("sha256:"))
                built(name).also { assertEquals(input.removePrefix("sha256:"), sha256(it).toHexString(), name) }
            else input.hexToByteArray()
            val result = try {
                decode(bytes)
                "ok"
            } catch (e: IllegalStateException) {
                e.message
            }
            assertEquals(expected, result, name)
        }
    }

    /** The inputs too large to print, built as the harness built them (their sha256 is checked). */
    fun built(name: String): ByteArray {
        fun zeros(count: Int, head: ByteArray) = head + ByteArray(count)
        val text = { length: Int -> byteArrayOf(0x79, (length shr 8).toByte(), length.toByte()) + ByteArray(length) { 'a'.code.toByte() } }
        return when (name) {
            "items_4096" -> zeros(4095, byteArrayOf(0x99.toByte(), 0x0f, 0xff.toByte()))
            "items_4097" -> zeros(4096, byteArrayOf(0x99.toByte(), 0x10, 0x00))
            "items_nested_4097" -> byteArrayOf(0x82.toByte()) +
                zeros(2047, byteArrayOf(0x99.toByte(), 0x07, 0xff.toByte())) +
                zeros(2047, byteArrayOf(0x99.toByte(), 0x07, 0xff.toByte()))
            "items_exhausted" -> byteArrayOf(0x82.toByte()) + zeros(4094, byteArrayOf(0x99.toByte(), 0x0f, 0xfe.toByte())) + byteArrayOf(0)
            "wire_max" -> ByteArray(MAX_WIRE).also {
                val length = MAX_WIRE - 5
                it[0] = 0x5a
                for (i in 0 until 4) it[1 + i] = (length shr (24 - 8 * i)).toByte()
            }
            "wire_over" -> ByteArray(MAX_WIRE + 1)
            "text_256" -> text(256)
            "text_257" -> text(257)
            else -> fail("no builder for $name")
        }
    }
}
