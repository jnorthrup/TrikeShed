package borg.trikeshed.util.oroboros

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series

class ForgeHomeTest {
    private val fileOps = FakeFileOperations()

    @Test
    fun testValidPaths() {
        val resolved = ForgeHome.resolveSafe("/base", "a/b/c", fileOps)
        assertEquals("/base/a/b/c", resolved)
    }

    @Test
    fun testEmptySegments() {
        assertFailsWith<IllegalArgumentException> {
            ForgeHome.resolveSafe("/base", "a//b///c", fileOps)
        }
    }

    @Test
    fun testEmptyPath() {
        val resolved = ForgeHome.resolveSafe("/base", "", fileOps)
        assertEquals("/base", resolved)
    }

    @Test
    fun testAbsolutePaths() {
        assertFailsWith<IllegalArgumentException> {
            ForgeHome.resolveSafe("/base", "/a/b", fileOps)
        }
    }

    @Test
    fun testPathTraversal() {
        assertFailsWith<IllegalArgumentException> {
            ForgeHome.resolveSafe("/base", "a/../b", fileOps)
        }
    }

    @Test
    fun testReservedDirectories() {
        assertFailsWith<IllegalArgumentException> {
            ForgeHome.resolveSafe("/base", "a/.git/b", fileOps)
        }
        assertFailsWith<IllegalArgumentException> {
            ForgeHome.resolveSafe("/base", ".pijul/b", fileOps)
        }
        assertFailsWith<IllegalArgumentException> {
            ForgeHome.resolveSafe("/base", "a/.oroboros", fileOps)
        }
    }

    @Test
    fun testResolveAgentPath() {
        // Just mock resolvePath to simple string concat with slash
        val mockFileOps = object : FileOperations by fileOps {
            override fun resolvePath(vararg parts: String): String = parts.joinToString("/")
        }
        // Instead of mocking SystemOperations, just assert based on behavior
        val resolvedBase = ForgeHome.resolve("test_agent", mockFileOps)
        val finalPath = ForgeHome.resolveAgentPath("test_agent", "data/file.txt", mockFileOps)
        assertEquals("$resolvedBase/data/file.txt", finalPath)
    }
}
