package borg.trikeshed.miniduck.columnar

import kotlin.test.*

class IndexCursorSeekTest {
    @Test fun `IndexCursor preScan builds lookup structure`() {
        val cursor = object : IndexCursor {
            override fun preScan(indexPath: String) { TODO("Not implemented") }
            override fun seek(target: Long): Boolean { TODO("Not implemented") }
            override fun next(): Boolean { TODO("Not implemented") }
            override fun current(): Long { TODO("Not implemented") }
            override fun close() { TODO("Not implemented") }
        }
        assertFailsWith<TODOError> { cursor.preScan("/nonexistent") }
    }
    
    @Test fun `IndexCursor seek fails when not preScanned`() {
        val cursor = object : IndexCursor {
            override fun preScan(indexPath: String) { TODO("Not implemented") }
            override fun seek(target: Long): Boolean { TODO("Not implemented") }
            override fun next(): Boolean { TODO("Not implemented") }
            override fun current(): Long { TODO("Not implemented") }
            override fun close() { TODO("Not implemented") }
        }
        assertFailsWith<TODOError> { cursor.seek(100) }
    }
    
    @Test fun `IndexCursor next fails when not preScanned`() {
        val cursor = object : IndexCursor {
            override fun preScan(indexPath: String) { TODO("Not implemented") }
            override fun seek(target: Long): Boolean { TODO("Not implemented") }
            override fun next(): Boolean { TODO("Not implemented") }
            override fun current(): Long { TODO("Not implemented") }
            override fun close() { TODO("Not implemented") }
        }
        assertFailsWith<TODOError> { cursor.next() }
    }
    
    @Test fun `IndexCursor current fails when not preScanned`() {
        val cursor = object : IndexCursor {
            override fun preScan(indexPath: String) { TODO("Not implemented") }
            override fun seek(target: Long): Boolean { TODO("Not implemented") }
            override fun next(): Boolean { TODO("Not implemented") }
            override fun current(): Long { TODO("Not implemented") }
            override fun close() { TODO("Not implemented") }
        }
        assertFailsWith<TODOError> { cursor.current() }
    }
    
    @Test fun `IndexCursor close releases resources`() {
        val cursor = object : IndexCursor {
            override fun preScan(indexPath: String) { TODO("Not implemented") }
            override fun seek(target: Long): Boolean { TODO("Not implemented") }
            override fun next(): Boolean { TODO("Not implemented") }
            override fun current(): Long { TODO("Not implemented") }
            override fun close() { TODO("Not implemented") }
        }
        assertFailsWith<TODOError> { cursor.close() }
    }
}
