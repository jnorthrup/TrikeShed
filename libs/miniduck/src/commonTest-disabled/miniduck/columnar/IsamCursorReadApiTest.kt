package borg.trikeshed.miniduck.columnar

import kotlin.test.*

class IsamCursorReadApiTest {
    @Test fun `IsamCursor columnSlice fails when not implemented`() {
        assertFailsWith<TODOError> {
            val cursor = IsamCursor.open("/dummy")
            cursor.columnSlice("col", 0, 10)
        }
    }
    
    @Test fun `IsamCursor predicatePushdown fails when not implemented`() {
        assertFailsWith<TODOError> {
            val cursor = IsamCursor.open("/dummy")
            cursor.predicatePushdown { true }
        }
    }
    
    @Test fun `IsamCursor columnNames returns loaded columns`() {
        assertFailsWith<TODOError> {
            val cursor = IsamCursor.open("/dummy")
            cursor.columnNames
        }
    }
}
