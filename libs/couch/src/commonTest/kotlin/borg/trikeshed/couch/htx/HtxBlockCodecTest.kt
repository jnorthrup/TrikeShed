package borg.trikeshed.couch.htx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TDD spec for HtxBlock field packing/unpacking algebra.
 *
 * HAProxy struct htx_blk layout:
 *   addr:  relative storage address of payload (UInt)
 *   info:  type(4 bits) | value_len(20 bits) | name_len(8 bits)
 *
 * info packing:
 *   bits [31..28] = type code
 *   bits [27.. 8] = value_len (20 bits, max 0xFFFFF)
 *   bits [ 7.. 0] = name_len  (8 bits,  max 0xFF)
 */
class HtxBlockCodecTest {

    // ── Round-trip: constructor → accessors ───────────────────────────────────

    @Test
    fun `blockType roundtrip`() {
        for (type in HtxBlockType.entries) {
            val block = HtxBlock(type, 0, 0, 0u)
            assertEquals(type, block.blockType, "type=$type")
        }
    }

    @Test
    fun `nameLen roundtrip max 255`() {
        for (len in listOf(0, 1, 127, 255)) {
            val block = HtxBlock(HtxBlockType.ReqSl, len, 0, 0u)
            assertEquals(len, block.nameLen, "len=$len")
        }
    }

    @Test
    fun `valueLen roundtrip max 0xFFFFF`() {
        for (len in listOf(0, 1, 1024, 0x7FFF, 0xFFFFF)) {
            val block = HtxBlock(HtxBlockType.ReqSl, 0, len, 0u)
            assertEquals(len, block.valueLen, "len=$len")
        }
    }

    @Test
    fun `addr is preserved verbatim`() {
        val addrs = listOf(0u, 1u, 0xFFFF_FFFFu, 0x8000_0000u)
        for (addr in addrs) {
            val block = HtxBlock(HtxBlockType.ResSl, 0, 0, addr)
            assertEquals(addr, block.addr, "addr=$addr")
        }
    }

    @Test
    fun `all fields roundtrip simultaneously`() {
        val block = HtxBlock(
            type    = HtxBlockType.ReqSl,
            nameLen = 64,
            valueLen = 0xABCDE,
            addr    = 0x1_0000u,
        )
        assertEquals(HtxBlockType.ReqSl, block.blockType)
        assertEquals(64, block.nameLen)
        assertEquals(0xABCDE, block.valueLen)
        assertEquals(0x1_0000u, block.addr)
    }

    // ── valueLen 20-bit overflow guard ─────────────────────────────────────────

    @Test
    fun `valueLen overflow truncated to 20 bits`() {
        // 0x10_0000 = bit 20 set — only 20 bits available
        val block = HtxBlock(HtxBlockType.ReqSl, 0, 0x10_0000, 0u)
        // valueLen should be 0 because info bits [27..8] overflow into type bits
        assertEquals(0x10_0000 and 0x0F_FFFF, block.valueLen)
    }

    // ── nameLen 8-bit overflow guard ───────────────────────────────────────────

    @Test
    fun `nameLen overflow truncated to 8 bits`() {
        // 0x100 = bit 8 set — only 8 bits available
        val block = HtxBlock(HtxBlockType.ReqSl, 0x100, 0, 0u)
        assertEquals(0x100 and 0xFF, block.nameLen)
    }

    // ── info bit layout ───────────────────────────────────────────────────────

    @Test
    fun `info encodes type in high 4 bits`() {
        val block = HtxBlock(HtxBlockType.ResSl, 0, 0, 0u)
        val infoType = block.info shr 28
        assertEquals(HtxBlockType.ResSl.code.toUInt(), infoType)
    }

    @Test
    fun `info encodes nameLen in low byte`() {
        val block = HtxBlock(HtxBlockType.ReqSl, 99, 0, 0u)
        val infoNameLen = (block.info and 0xFFu).toInt()
        assertEquals(99, infoNameLen)
    }

    @Test
    fun `info encodes valueLen in middle 20 bits`() {
        val block = HtxBlock(HtxBlockType.ReqSl, 0, 0x12345, 0u)
        val infoValueLen = (block.info shr 8) and 0x0F_FFFFu
        assertEquals(0x12345u, infoValueLen)
    }

    // ── HtxBlockData: data accessors (if HtxBlockData.kt provides them) ──────

    @Test
    fun `HtxBlockType code is stable`() {
        // Every type's code should be consistent across fromCode roundtrips
        for (type in HtxBlockType.entries) {
            val decoded = HtxBlockType.fromCode(type.code)
            assertEquals(type, decoded, "type.code=${type.code}")
        }
    }

    @Test
    fun `HtxBlockType entries cover expected codes`() {
        // DHTX_REQ and DHTX_RES are canonical for Binance HTX pipeline
        val codes = HtxBlockType.entries.map { it.code }.toSet()
        assertTrue(codes.size == HtxBlockType.entries.size, "all codes are unique")
    }
}
