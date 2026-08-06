package borg.trikeshed.userspace.nio.file.spi

import borg.trikeshed.common.createTempDirectory
import kotlinx.cinterop.convert
import platform.posix.chmod
import kotlin.test.Test
import kotlin.test.assertTrue

class LinuxFileOperationsTest {

    @Test
    fun deleteRecursivelyHandlesUnreadableDirectories() {
        val files = LinuxFileOperations()
        val baseDir = createTempDirectory("trikeshed-native")
        val path = "$baseDir/unreadable_dir"
        files.mkdirs(path)
        assertTrue(files.isDir(path))

        // Make the directory unreadable so opendir fails
        chmod(path, 0u.convert())

        try {
            // This should not throw an exception (such as NullPointerException) if opendir returns null
            files.deleteRecursively(path)
        } finally {
            // Restore permissions so we can clean up
            chmod(path, 0x1FFu.convert())
            files.deleteRecursively(baseDir)
        }
    }
}
