package borg.trikeshed.userspace.nio.file.spi

import borg.trikeshed.lib.Files

class JsFileOperations : FileOperations {

    override fun open(path: String, readOnly: Boolean): Int = path.hashCode()
    override fun close(fd: Int): Int = 0
    override fun size(fd: Int): Long = 0L

    override fun readAllLines(f: String) = Files.readAllLines(f)
    override fun readAllBytes(f: String) = Files.readAllBytes(f)
    override fun readString(f: String) = Files.readString(f)
    override fun write(f: String, bytes: ByteArray) { Files.write(f, bytes) }
    override fun write(f: String, lines: List<String>) { Files.write(f, lines) }
    override fun write(f: String, string: String) { Files.write(f, string) }
    override fun cwd() = Files.cwd()
    override fun exists(f: String) = Files.exists(f)
    override fun streamLines(f: String, bs: Int) = Files.streamLines(f, bs)
    override fun iterateLines(f: String, bs: Int) = Files.iterateLines(f, bs)
    override fun listDir(p: String) = Files.listDir(p)
    override fun isDir(p: String) = Files.isDir(p)
    override fun isFile(p: String) = Files.isFile(p)
    override fun mkdirs(p: String) { Files.mkdirs(p) }
    override fun deleteRecursively(p: String) { Files.deleteRecursively(p) }
    override fun resolvePath(vararg p: String) = Files.resolvePath(*p)
    override fun readZip(p: String) = Files.readZip(p)
    override fun createTempDir(p: String) = Files.createTempDir(p)
}
