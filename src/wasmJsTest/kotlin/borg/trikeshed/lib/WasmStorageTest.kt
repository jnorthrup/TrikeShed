package borg.trikeshed.lib

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.js.ExperimentalWasmJsInterop

class WasmStorageTest {

    @OptIn(ExperimentalWasmJsInterop::class)
    @Test
    fun testStorageSetExceptionHandling() {
        backupAndMock()
        try {
            // This should trigger the catch block in storageSet and return false
            val result = storageSet("testFailKey", "testValue")
            assertFalse(result, "storageSet should return false when localStorage.setItem throws an exception")
        } finally {
            restore()
        }
    }
}

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("() => { globalThis.mockStorageThrow = true; }")
private external fun backupAndMock()

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("() => { globalThis.mockStorageThrow = false; }")
private external fun restore()
