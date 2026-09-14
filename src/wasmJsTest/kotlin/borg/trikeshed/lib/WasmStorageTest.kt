package borg.trikeshed.lib

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import borg.trikeshed.common.Files
import borg.trikeshed.platform.PlatformClock
import borg.trikeshed.platform.PlatformHost
import borg.trikeshed.userspace.nio.IOException
import borg.trikeshed.util.oroboros.FileWatchReactorElement
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

class WasmStorageTest {
    private var now = 1_000L

    @OptIn(ExperimentalWasmJsInterop::class)
    private fun withStorage(body: () -> Unit) {
        val previous = PlatformHost.default
        installStorage()
        PlatformHost.register(object : PlatformHost by previous {
            override val clock = object : PlatformClock by previous.clock {
                override fun nowMillis(): Long = now
            }
        })
        try { body() } finally {
            restore()
            rejectRemoval(false)
            try { rm("/due-diligence") } finally {
                PlatformHost.register(previous)
                uninstallStorage()
            }
        }
    }

    @OptIn(ExperimentalWasmJsInterop::class)
    @Test
    fun testStorageSetExceptionHandling() = withStorage {
        backupAndMock()
        try {
            // This should trigger the catch block in storageSet and return false
            val result = storageSet("testFailKey", "testValue")
            assertFalse(result, "storageSet should return false when localStorage.setItem throws an exception")
        } finally {
            restore()
        }
    }

    @Test
    fun failedPersistentOverwritePreservesThePriorFileAndTime() = withStorage {
        val path = "/due-diligence/overwrite"
        Files.write(path, "first")
        now = 2_000L
        backupAndMock()
        assertFailsWith<IOException> { Files.write(path, "second") }
        restore()
        assertEquals("first", Files.readString(path))
        assertEquals(1_000L, Files.lastModified(path))
    }

    @Test
    fun rejectedRemovalDoesNotReportSuccessOrEraseTheTimestamp() = withStorage {
        val path = "/due-diligence/removal"
        Files.write(path, "kept")
        rejectRemoval(true)
        assertFailsWith<IOException> { Files.deleteRecursively(path) }
        rejectRemoval(false)
        assertEquals("kept", Files.readString(path))
        assertEquals(1_000L, Files.lastModified(path))
    }

    @Test
    fun legacyFilesRemainReadableAndUpdatesRetainTheirTime() = withStorage {
        val path = "/due-diligence/legacy"
        storageSet(fileKey(path), "6162")
        storageSet("trikeshed:browser:mtime:" + path, "17")
        assertEquals("ab", Files.readString(path))
        assertEquals(17L, Files.lastModified(path))
        Files.write(path, "cd")
        assertEquals("cd", Files.readString(path))
        assertEquals(1_000L, Files.lastModified(path))
        Files.deleteRecursively(path)
        assertFalse(Files.exists(path))
        assertEquals(0L, Files.lastModified(path))
    }

    @Test
    fun unavailableWatcherDoesNotKeepItsParentAlive() = runTest {
        val parent = Job()
        try {
            assertFailsWith<NotImplementedError> {
                FileWatchReactorElement("/due-diligence", parent, 1, emptyList(), emptyList(), emptySet(), emptySet()).open()
            }
            parent.complete()
            withTimeout(1_000) { parent.join() }
        } finally { parent.cancel() }
    }
}

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("""() => {
    const previous = Object.getOwnPropertyDescriptor(globalThis, 'localStorage');
    const values = new Map();
    globalThis.trikeshedStorageTest = { previous, writesFail: false, removalFails: false };
    Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: {
        setItem(k, v) { if (globalThis.trikeshedStorageTest.writesFail) throw new Error('quota'); values.set(k, v); },
        getItem(k) { return values.get(k) ?? null; },
        removeItem(k) { if (globalThis.trikeshedStorageTest.removalFails) throw new Error('denied'); values.delete(k); },
        key(i) { return [...values.keys()][i] ?? null; },
        get length() { return values.size; }
    }});
}""")
private external fun installStorage()

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("() => { const prior = globalThis.trikeshedStorageTest.previous; if (prior) Object.defineProperty(globalThis, 'localStorage', prior); else delete globalThis.localStorage; delete globalThis.trikeshedStorageTest; }")
private external fun uninstallStorage()

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("(fail) => { globalThis.trikeshedStorageTest.removalFails = fail; }")
private external fun rejectRemoval(fail: Boolean)

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("() => { globalThis.trikeshedStorageTest.writesFail = true; }")
private external fun backupAndMock()

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("() => { globalThis.trikeshedStorageTest.writesFail = false; }")
private external fun restore()
