package borg.trikeshed.miniduck.columnar

import kotlin.test.*

class IndexCursorSeekTest {
    @Test fun `IndexCursor seek fails when not implemented`() {
        val cursor = object : IndexCursor {
            override fun seek(blockOffset: Long) { error("Not implemented") }
            override fun next(): Boolean { error("Not implemented") }
            override fun current(): Long { error("Not implemented") }
        }
        assertFails { cursor.seek(100) }
    }

    @Test fun `IndexCursor next fails when not implemented`() {
        val cursor = object : IndexCursor {
            override fun seek(blockOffset: Long) { error("Not implemented") }
            override fun next(): Boolean { error("Not implemented") }
            override fun current(): Long { error("Not implemented") }
        }
        assertFails { cursor.next() }
    }

    @Test fun `IndexCursor current fails when not implemented`() {
        val cursor = object : IndexCursor {
            override fun seek(blockOffset: Long) { error("Not implemented") }
            override fun next(): Boolean { error("Not implemented") }
            override fun current(): Long { error("Not implemented") }
        }
        assertFails { cursor.current() }
    }
}
