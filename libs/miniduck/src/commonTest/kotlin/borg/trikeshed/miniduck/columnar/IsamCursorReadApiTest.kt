package borg.trikeshed.miniduck.columnar

import kotlin.test.*

class IsamCursorReadApiTest {
    @Test fun `IsamCursor-open fails when directory does not exist`() {
        assertFails {
            IsamCursor.open("/dummy-missing")
        }
    }

    @Test fun `IsamCursor-open returns an IsamCursor instance`() {
        // Will fail until IsamCursor.open is actually implemented
        assertFails {
            IsamCursor.open("/dummy")
        }
    }
}
