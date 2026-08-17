package borg.trikeshed.userspace.containment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FusePathCanonicalizerTest {

    @Test
    fun testCanonicalizeDirectory() {
        val canonicalizer = createFusePathCanonicalizer("instance1")
        val result = canonicalizer.canonicalizePath("my_folder", isDirectory = true)

        assertTrue(result.startsWith("dir_"))
        assertEquals(12, result.length) // "dir_" + 8 hex chars

        val resolved = canonicalizer.resolveOriginal(result)
        assertEquals("my_folder", resolved)
    }

    @Test
    fun testCanonicalizeFile() {
        val canonicalizer = createFusePathCanonicalizer("instance1")
        val result = canonicalizer.canonicalizePath("my_file.txt", isDirectory = false)

        assertTrue(result.startsWith("file_"))
        assertEquals(13, result.length) // "file_" + 8 hex chars

        val resolved = canonicalizer.resolveOriginal(result)
        assertEquals("my_file.txt", resolved)
    }

    @Test
    fun testCrossInstanceIsolation() {
        val canonicalizer1 = createFusePathCanonicalizer("instance1")
        val canonicalizer2 = createFusePathCanonicalizer("instance2")

        val result1 = canonicalizer1.canonicalizePath("shared_file", isDirectory = false)
        val result2 = canonicalizer2.canonicalizePath("shared_file", isDirectory = false)

        assertNotEquals(result1, result2, "Paths should map to different canonical names across instances")
    }

    @Test
    fun testResolveUnknown() {
        val canonicalizer = createFusePathCanonicalizer("instance1")
        assertNull(canonicalizer.resolveOriginal("file_deadbeef"))
    }

    @Test
    fun testStableCanonicalization() {
        val canonicalizer = createFusePathCanonicalizer("instance1")
        val result1 = canonicalizer.canonicalizePath("test_file", isDirectory = false)
        val result2 = canonicalizer.canonicalizePath("test_file", isDirectory = false)

        assertEquals(result1, result2, "Canonicalization for the same path should return the same name")
    }
}
