package borg.trikeshed.util.oroboros

import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.coroutines.CoroutineContext

class FakeFileOperations : FileOperations {
    val files = mutableMapOf<String, ByteArray>()
    val dirs = mutableSetOf<String>()
    
    // We simulate special files by keeping them in specialFiles set instead of files/dirs
    val specialFiles = mutableSetOf<String>()

    override fun open(path: String, readOnly: Boolean): Int = throw UnsupportedOperationException()
    override fun readAllLines(filename: String): List<String> = throw UnsupportedOperationException()
    override fun readAllBytes(filename: String): ByteArray {
        if (specialFiles.contains(filename)) throw RuntimeException("Cannot read special file")
        return files[filename] ?: throw RuntimeException("File not found: $filename")
    }
    override fun readString(filename: String): String = throw UnsupportedOperationException()
    override fun write(filename: String, bytes: ByteArray) { files[filename] = bytes }
    override fun write(filename: String, lines: List<String>) { throw UnsupportedOperationException() }
    override fun write(filename: String, string: String) { throw UnsupportedOperationException() }
    override fun cwd(): String = "/tmp/testroot"
    override fun exists(filename: String): Boolean = files.containsKey(filename) || dirs.contains(filename) || specialFiles.contains(filename)
    override fun streamLines(fileName: String, bufsize: Int): Sequence<Join<Long, ByteArray>> = throw UnsupportedOperationException()
    override fun iterateLines(fileName: String, bufsize: Int): Iterable<Join<Long, Series<Byte>>> = throw UnsupportedOperationException()
    
    override fun listDir(path: String): List<String> {
        val prefix = if (path.endsWith("/")) path else "$path/"
        val result = mutableSetOf<String>()
        val allPaths = files.keys + dirs + specialFiles
        for (p in allPaths) {
            if (p != path && p.startsWith(prefix)) {
                val rel = p.substring(prefix.length)
                val slash = rel.indexOf('/')
                if (slash == -1) {
                    result.add(rel)
                } else {
                    result.add(rel.substring(0, slash))
                }
            }
        }
        return result.toList()
    }
    
    override fun isDir(path: String): Boolean = dirs.contains(path)
    override fun isFile(path: String): Boolean = files.containsKey(path)
    override fun mkdirs(path: String) { dirs.add(path) }
    override fun deleteRecursively(path: String) { throw UnsupportedOperationException() }
    override fun resolvePath(vararg parts: String): String = parts.joinToString("/")
    override fun readZip(path: String): List<Pair<String, ByteArray>> = throw UnsupportedOperationException()
    override fun createTempDir(prefix: String): String = throw UnsupportedOperationException()
    override fun close(fd: Int): Int = throw UnsupportedOperationException()
    override fun size(fd: Int): Long = throw UnsupportedOperationException()
}

class ProjectTreeAttachmentsTest {

    @Test
    fun testBuildTree() {
        val ops = FakeFileOperations()
        ops.dirs.add("root")
        ops.dirs.add("root/.git")
        ops.dirs.add("root/src")
        
        ops.files["root/README.md"] = "Hello Markdown".encodeToByteArray()
        ops.files["root/.git/HEAD"] = "ref: refs/heads/main\n".encodeToByteArray()
        ops.files["root/src/File.kt"] = "class Foo".encodeToByteArray()
        
        val brokenSymlinks = mutableListOf<String>()
        val result = ProjectTreeAttachments.build(
            fileOps = ops,
            rootPath = "root",
            ignorePredicate = { false },
            onBrokenSymlinkOrSpecialFile = { brokenSymlinks.add(it) }
        )
        
        assertEquals(3, result.size)
        assertTrue(brokenSymlinks.isEmpty())
        
        val readme = result["README.md"]!!
        assertEquals("text/markdown", readme.contentType)
        assertEquals(14, readme.length)
        assertEquals("Hello Markdown".encodeToByteArray().toList(), readme.data.toList())
        
        val head = result[".git/HEAD"]!!
        assertEquals("application/octet-stream", head.contentType)
        assertEquals(21, head.length)
        
        val kt = result["src/File.kt"]!!
        assertEquals("text/kotlin", kt.contentType)
        assertEquals(9, kt.length)
    }
    
    @Test
    fun testIgnorePredicate() {
        val ops = FakeFileOperations()
        ops.dirs.add("root")
        ops.dirs.add("root/build")
        ops.files["root/file1.txt"] = "1".encodeToByteArray()
        ops.files["root/build/out.bin"] = "2".encodeToByteArray()
        
        val result = ProjectTreeAttachments.build(
            fileOps = ops,
            rootPath = "root",
            ignorePredicate = { it.startsWith("build/") || it == "build" }
        )
        
        assertEquals(1, result.size)
        assertTrue(result.containsKey("file1.txt"))
    }
    
    @Test
    fun testSpecialFilesReported() {
        val ops = FakeFileOperations()
        ops.dirs.add("root")
        ops.files["root/normal.txt"] = "1".encodeToByteArray()
        ops.specialFiles.add("root/broken_symlink")
        
        val reported = mutableListOf<String>()
        val result = ProjectTreeAttachments.build(
            fileOps = ops,
            rootPath = "root",
            onBrokenSymlinkOrSpecialFile = { reported.add(it) }
        )
        
        assertEquals(1, result.size)
        assertTrue(result.containsKey("normal.txt"))
        
        assertEquals(1, reported.size)
        assertEquals("broken_symlink", reported[0])
    }
}
