package borg.trikeshed.common

/**
 * Path-shaped file handle for commonMain — the [java.io.File] call-shape, owned by the
 * Files SPI. A [Path] is a NAME, not a descriptor: every operation resolves through the
 * platform actual, so no OS call happens in commonMain. Ported jvmMain bodies keep their
 * `File(...)` call-shape while the IO stays behind the SPI.
 */
@JvmInline
value class Path(val absolutePath: String) {

    /** Last path segment. */
    val name: String
        get() = absolutePath.substringAfterLast('/').ifEmpty { absolutePath }

    /** The parent path, or null at the root. */
    val parentFile: Path?
        get() {
            val idx = absolutePath.lastIndexOf('/')
            return if (idx <= 0) null else Path(absolutePath.substring(0, idx))
        }

    fun resolve(child: String): Path = Path(Files.resolvePath(absolutePath, child))

    fun exists(): Boolean = Files.exists(absolutePath)
    fun isDirectory(): Boolean = Files.isDir(absolutePath)
    fun isFile(): Boolean = Files.isFile(absolutePath)
    fun mkdirs(): Boolean = runCatching { Files.mkdirs(absolutePath) }.isSuccess
    fun delete(): Boolean = runCatching { Files.deleteRecursively(absolutePath) }.isSuccess

    fun readLines(): List<String> = Files.readAllLines(absolutePath)
    fun readBytes(): ByteArray = Files.readAllBytes(absolutePath)
    fun readText(): String = Files.readString(absolutePath)
    fun writeBytes(bytes: ByteArray) = Files.write(absolutePath, bytes)
    fun writeText(text: String) = Files.write(absolutePath, text)

    fun listFiles(): List<Path> = Files.listDir(absolutePath).map { resolve(it) }

    /** Lexically-normalized absolute spelling — the SPI resolves symlinks where the platform can. */
    val canonicalFile: Path
        get() = Path(Files.resolvePath(absolutePath))

    /** Path separator spelling of this path. */
    val path: String get() = absolutePath


    override fun toString(): String = absolutePath
}

/** The type + constructor spelling ported bodies use: `File("a/b")`, `File(parent, child)`. */
typealias File = Path

