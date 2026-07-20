package borg.trikeshed.reflink

import borg.trikeshed.job.ContentId
import kotlin.test.Test
import kotlin.test.assertEquals

class ReflinkScannerTest {
    @Test
    fun testFixedBlockChunking() {
        val scanner = FixedBlockReflinkScanner(blockSize = 4)
        val data = "123456789".encodeToByteArray() // 9 bytes
        
        val chunks = scanner.scan(data)
        
        assertEquals(3, chunks.size) // 4, 4, 1 bytes
        assertEquals(ContentId.of("1234".encodeToByteArray()), chunks[0])
        assertEquals(ContentId.of("5678".encodeToByteArray()), chunks[1])
        assertEquals(ContentId.of("9".encodeToByteArray()), chunks[2])
    }
}
