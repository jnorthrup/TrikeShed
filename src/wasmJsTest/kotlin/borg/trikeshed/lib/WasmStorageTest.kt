package borg.trikeshed.lib

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.js.JsAny

class WasmStorageTest {

    @Test
    fun testStorageSetExceptionHandling() {
        val original = backupAndMock()
        try {
            // This should trigger the catch block in storageSet and return false
            val result = storageSet("testFailKey", "testValue")
            assertFalse(result, "storageSet should return false when localStorage.setItem throws an exception")
        } finally {
            restore(original)
        }
    }
}

@JsFun("""
    () => {
        var original = localStorage.setItem;
        localStorage.setItem = function() {
            throw new Error('Mock Storage Error');
        };
        return original;
    }
""")
private external fun backupAndMock(): JsAny

@JsFun("""
    (orig) => {
        localStorage.setItem = orig;
    }
""")
private external fun restore(original: JsAny)
