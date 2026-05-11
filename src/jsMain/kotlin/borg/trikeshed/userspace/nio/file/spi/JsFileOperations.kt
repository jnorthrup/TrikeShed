package borg.trikeshed.userspace.nio.file.spi

import borg.trikeshed.lib.*

/**
 * JS platform FileOperations — delegates to the Node.js helper functions
 * defined in [borg.trikeshed.lib] (jsReadString, fs, path, etc).
 */
class JsFileOperations : FileOperations {

    override fun readAllLines(filename: String): List<String> =
        jsReadString(filename).replace("\r\n", "\n").lines()

    override fun readAllBytes(filename: String): ByteArray = jsReadBytes(filename)

    override fun readString(filename: String): String = jsReadString(filename)

    override fun write(filename: String, bytes: ByteArray) { jsWriteBytes(filename, bytes) }

    override fun write(filename: String, lines: List<String>) { write(filename, lines.joinToString("\n")) }

    override fun write(filename: String, string: String) { jsWriteString(filename, string) }

    override fun cwd(): String = jsCwd()

    override fun exists(filename: String): Boolean = jsExists(filename)

    override fun streamLines(fileName: String, bufsize: Int): Sequence<Join<Long, ByteArray>> =
        streamByteLines(readAllBytes(fileName))

    override fun iterateLines(fileName: String, bufsize: Int): Iterable<Join<Long, Series<Byte>>> =
        streamLines(fileName, bufsize).map { (offset, bytes) -> offset j bytes.toSeries() }.asIterable()

    override fun listDir(path: String): List<String> {
        val entries: dynamic = fs.readdirSync(path)
        val result = mutableListOf<String>()
        val length = entries.length as Int
        for (i in 0 until length) result.add(entries[i] as String)
        return result
    }

    override fun isDir(path: String): Boolean {
        val stat: dynamic = fs.statSync(path)
        return (stat.isDirectory() as Boolean)
    }

    override fun isFile(path: String): Boolean {
        val stat: dynamic = fs.statSync(path)
        return (stat.isFile() as Boolean)
    }

    override fun mkdirs(path: String) { jsMkdir(path) }

    override fun deleteRecursively(path: String) { jsRm(path) }

    override fun resolvePath(vararg parts: String): String =
        path.join(jsCwd(), parts.joinToString("/")) as String

    override fun readZip(path: String): List<Pair<String, ByteArray>> = TODO("readZip JS")

    override fun createTempDir(prefix: String): String {
        val dir = path.join(os.tmpdir(), "$prefix-${kotlin.random.Random.nextInt(1_000_000)}") as String
        jsMkdir(dir)
        return dir
    }
}
