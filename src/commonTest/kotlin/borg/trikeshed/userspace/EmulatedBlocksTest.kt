package borg.trikeshed.userspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class EmulatedBlocksTest {
    @Test fun blocksArePowerOfTwoPagesRecycledLastFirstAndUncleared() {
        val blocks = EmulatedBlocks(hold = 2)
        val page = blocks.acquire(10)
        assertEquals(EMULATED_BLOCK_BYTES, page.size)
        assertEquals(EMULATED_BLOCK_BYTES, blocks.acquire(EMULATED_BLOCK_BYTES).size)
        assertEquals(2 * EMULATED_BLOCK_BYTES, blocks.acquire(EMULATED_BLOCK_BYTES + 1).size)
        val other = blocks.acquire(1)
        page.fill(7)
        blocks.release(other)
        blocks.release(page)
        val again = blocks.acquire(100)
        assertSame(page, again, "the block released last is handed out first")
        assertEquals(7.toByte(), again[EMULATED_BLOCK_BYTES - 1], "a recycled block is not cleared")
        assertSame(other, blocks.acquire(1))
        assertNotSame(page, blocks.acquire(1), "an empty shelf allocates")
    }

    @Test fun releaseKeepsAtMostHoldBlocksOfASizeAndDropsArraysItDidNotSize() {
        val blocks = EmulatedBlocks(hold = 1)
        val first = blocks.acquire(1)
        val second = blocks.acquire(1)
        blocks.release(first)
        blocks.release(second)
        assertEquals(1, blocks.held)
        blocks.release(ByteArray(EMULATED_BLOCK_BYTES + 1))
        blocks.release(ByteArray(EMULATED_BLOCK_BYTES / 2))
        assertEquals(1, blocks.held)
        assertSame(first, blocks.acquire(EMULATED_BLOCK_BYTES))
        assertEquals(0, blocks.held)
    }
}
