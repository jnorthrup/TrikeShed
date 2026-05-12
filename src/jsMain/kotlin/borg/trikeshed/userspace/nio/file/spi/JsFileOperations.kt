package borg.trikeshed.userspace.nio.file.spi

import borg.trikeshed.lib.*

/**
 * JS platform FileOperations — delegates to the Node.js helper functions
 * defined in [borg.trikeshed.lib] (jsReadString, fs, path, etc).
 */
class JsFileOperations : FileOperations {

    override fun readAllLines(filename: CharSequence): List<String> =
        jsReadString(filename).toString().replace("\r\n", "\n").lines()

    override fun readAllBytes(filename: CharSequence): ByteArray = jsReadBytes(filename)

    override fun readString(filename: CharSequence): String = jsReadString(filename).toString()

    override fun write(filename: CharSequence, bytes: ByteArray) { jsWriteBytes(filename, bytes) }

    override fun write(filename: CharSequence, lines: List<CharSequence>) { write(filename, lines.joinToString("\n")) }

    override fun write(filename: CharSequence, string: CharSequence) { jsWriteString(filename, string) }

    override fun cwd(): String = jsCwd().toString()

    override fun exists(filename: CharSequence): Boolean = jsExists(filename)

    override fun streamLines(fileName: CharSequence, bufsize: Int): Sequence<Join<Long, ByteArray>> =
        streamByteLines(readAllBytes(fileName))

    override fun iterateLines(fileName: CharSequence, bufsize: Int): Iterable<Join<Long, Series<Byte>>> =
        streamLines(fileName, bufsize).map { (offset, bytes) -> offset j bytes.toSeries() }.asIterable()

    override fun listDir(path: CharSequence): List<String> {
        val entries: dynamic = fs.readdirSync(path)
        val result = mutableListOf<String>()
        val length = entries.length as Int
        for (i in 0 until length) result.add(entries[i] as String)
        return result
    }

    override fun isDir(path: CharSequence): Boolean {
        val stat: dynamic = fs.statSync(path)
        return (stat.isDirectory() as Boolean)
    }

    override fun isFile(path: CharSequence): Boolean {
        val stat: dynamic = fs.statSync(path)
        return (stat.isFile() as Boolean)
    }

    override fun mkdirs(path: CharSequence) { jsMkdir(path) }

    override fun deleteRecursively(path: CharSequence) { jsRm(path) }

    override fun resolvePath(vararg parts: CharSequence): String =
        path.join(jsCwd(), parts.joinToString("/")) as String

    override fun readZip(path: CharSequence): List<Pair<String, ByteArray>> = TODO("readZip JS")

    override fun createTempDir(prefix: CharSequence): String {
        val dir = path.join(os.tmpdir(), "$prefix-${kotlin.random.Random.nextInt(1_000_000)}") as String
        jsMkdir(dir)
        return dir
    }
}
