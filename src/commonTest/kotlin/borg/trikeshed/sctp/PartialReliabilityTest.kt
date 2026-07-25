/*
 * Copyright (c) 2024-2026. The TrikeShed Authors.
 * Licensed under the AGPLv3.
 */
package borg.trikeshed.sctp

import borg.trikeshed.lib.size
import borg.trikeshed.lib.get
import kotlin.test.Test
import kotlin.test.assertEquals

class PartialReliabilityTest {
    @Test
    fun testPartialReliabilityDropsOldestUnacked() {
        val prBuffer = PartialReliabilityBuffer(capacity = 2)
        prBuffer.enqueue(1, "chunk1".encodeToByteArray())
        prBuffer.enqueue(2, "chunk2".encodeToByteArray())

        // At capacity, adding 3 should drop oldest (1)
        prBuffer.enqueue(3, "chunk3".encodeToByteArray())

        val chunks = prBuffer.getAllUnacked()
        assertEquals(2, chunks.size)
        assertEquals(2, chunks[0].first)
        assertEquals(3, chunks[1].first)
    }
}
