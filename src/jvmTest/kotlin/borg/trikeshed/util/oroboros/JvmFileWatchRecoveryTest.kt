package borg.trikeshed.util.oroboros

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class JvmFileWatchRecoveryTest {
    @Test
    fun attachesToAnInitiallyMissingRoot() = runBlocking(Dispatchers.IO) {
        val home = Files.createTempDirectory("watch-missing-")
        val root = home.resolve("classes")
        val watcher = JvmFileWatchReactorElement(root.toString(), includeGlobs = emptyList(), excludeGlobs = emptyList())
        try {
            watcher.open()
            Files.createDirectories(root.resolve("a/b"))
            root.resolve("a/b/Foo.class").writeText("compiled")
            assertEquals(FileEventType.CREATE, awaitFile(watcher, "a/b/Foo.class").type)
        } finally {
            watcher.close()
            home.toFile().deleteRecursively()
        }
    }

    @Test
    fun reattachesAfterTheEntireRootIsReplaced() = runBlocking(Dispatchers.IO) {
        val home = Files.createTempDirectory("watch-replace-")
        val root = Files.createDirectory(home.resolve("classes"))
        val watcher = JvmFileWatchReactorElement(root.toString(), includeGlobs = emptyList(), excludeGlobs = emptyList())
        try {
            watcher.open()
            root.toFile().deleteRecursively()
            delay(2500) // The macOS JDK watch provider polls every two seconds.
            Files.createDirectories(root.resolve("new/package"))
            val file = root.resolve("new/package/Bar.class")
            file.writeText("rebuilt")
            assertEquals(FileEventType.CREATE, awaitFile(watcher, "new/package/Bar.class").type)
            file.writeText("updated again")
            assertEquals(FileEventType.MODIFY, awaitFile(watcher, "new/package/Bar.class").type)
        } finally {
            watcher.close()
            home.toFile().deleteRecursively()
        }
    }

    @Test
    fun findsFilesInNewDirectoriesEvenWhenTheGlobOnlyMatchesFiles() = runBlocking(Dispatchers.IO) {
        val root = Files.createTempDirectory("watch-package-")
        val watcher = JvmFileWatchReactorElement(root.toString(), includeGlobs = listOf("**/*.class"), excludeGlobs = emptyList())
        try {
            watcher.open()
            Files.createDirectories(root.resolve("pkg/nested"))
            root.resolve("pkg/nested/Baz.class").writeText("compiled")
            assertEquals(FileEventType.CREATE, awaitFile(watcher, "pkg/nested/Baz.class").type)
        } finally {
            watcher.close()
            root.toFile().deleteRecursively()
        }
    }

    private suspend fun awaitFile(watcher: JvmFileWatchReactorElement, path: String): FileEvent = withTimeout(8000) {
        var event = watcher.events.receive()
        while (event.path != path) event = watcher.events.receive()
        event
    }
}
