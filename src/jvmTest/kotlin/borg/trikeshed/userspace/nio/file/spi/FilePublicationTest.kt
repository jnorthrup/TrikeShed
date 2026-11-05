package borg.trikeshed.userspace.nio.file.spi

import java.nio.file.Files
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import kotlin.test.*

class FilePublicationTest {
    @Test fun atomicReplacementPreservesTheOpenedReaderAndCreatesAncestors() {
        val files = JvmFileOperations()
        val root = Files.createTempDirectory("file-publication-")
        try {
            val target = root.resolve("a/b/value")
            files.writeAtomically(target.toString(), "first".encodeToByteArray())
            Files.newInputStream(target).use { retained ->
                files.writeAtomically(target.toString(), "second".encodeToByteArray())
                assertEquals("first", retained.readAllBytes().decodeToString())
                assertEquals("second", files.readString(target.toString()))
            }
        } finally { files.deleteRecursively(root.toString()) }
    }

    @Test fun directoryPublicationRefusesAFileAncestorAndAnExistingExclusiveLeaf() {
        val files = JvmFileOperations()
        val root = Files.createTempDirectory("directory-publication-")
        try {
            val occupied = root.resolve("occupied")
            files.write(occupied.toString(), "retained")
            assertFailsWith<FileSystemException> { files.mkdirs(occupied.resolve("child").toString()) }
            assertEquals("retained", files.readString(occupied.toString()))
            val leaf = root.resolve("exclusive")
            files.createDirectory(leaf.toString())
            files.write(leaf.resolve("value").toString(), "preserved")
            assertFailsWith<FileAlreadyExistsException> { files.createDirectory(leaf.toString()) }
            assertEquals("preserved", files.readString(leaf.resolve("value").toString()))
        } finally { files.deleteRecursively(root.toString()) }
    }

    @Test fun directoryPublicationFollowsAnExistingSymlinkWithoutReplacingIt() {
        val files = JvmFileOperations()
        val root = Files.createTempDirectory("directory-alias-")
        try {
            val real = root.resolve("real")
            files.createDirectory(real.toString())
            val alias = Files.createSymbolicLink(root.resolve("alias"), real)
            files.mkdirs(alias.resolve("a/b").toString())
            assertTrue(Files.isSymbolicLink(alias))
            assertTrue(Files.isSameFile(alias.resolve("a/b"), real.resolve("a/b")))
        } finally { files.deleteRecursively(root.toString()) }
    }
}
