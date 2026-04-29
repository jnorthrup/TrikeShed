package borg.trikeshed.miniduck.columnar

import kotlin.test.*

class IsamCursorPipelineTest {
    @Test
    fun open_throws_when_not_implemented() {
        assertFailsWith(Throwable::class) {
            IsamCursor.open("/nonexistent")
        }
    }
}
