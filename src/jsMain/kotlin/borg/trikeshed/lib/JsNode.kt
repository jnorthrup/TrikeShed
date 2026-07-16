package borg.trikeshed.lib

import kotlin.js.Date
import kotlin.random.Random

// Lazy Node.js module access — these require() calls MUST NOT execute at module
// load time or the browser bundle crashes (browsers have no require()).  Each
// is wrapped in a lazy delegate that only fires when a Node path actually calls it.

private fun requireModule(name: String): dynamic = js("require")(name)

val fs: dynamic get() = requireModule("fs")
val os: dynamic get() = requireModule("os")
val path: dynamic get() = requireModule("path")
val processObj: dynamic get() = js("process")
val Buffer: dynamic get() = js("globalThis.Buffer")

internal fun jsCwd(): String = processObj.cwd() as String

internal fun jsHomeDir(): String =
    (processObj.env.HOME as? String)
        ?: (processObj.env.USERPROFILE as? String)
        ?: jsCwd()

internal fun resolveTestPath(filename: String): String {
    if (fs.existsSync(filename) as Boolean) return filename
    var dir = processObj.cwd() as String
    while (dir != "/" && dir.isNotEmpty()) {
        val check = path.join(dir, "build.gradle.kts") as String
        if (fs.existsSync(check) as Boolean) {
            val candidate = path.join(dir, filename) as String
            if (fs.existsSync(candidate) as Boolean) {
                return candidate
            }
        }
        val parent = path.dirname(dir) as String
        if (parent == dir) break
        dir = parent
    }
    return filename
}

internal fun jsExists(filename: String): Boolean = fs.existsSync(resolveTestPath(filename)) as Boolean

internal fun jsReadString(filename: String): String = fs.readFileSync(resolveTestPath(filename), "utf8") as String

internal fun jsReadBytes(filename: String): ByteArray {
    val buffer: dynamic = fs.readFileSync(resolveTestPath(filename))
    val size = (buffer.length as Number).toInt()
    return ByteArray(size) { index -> (buffer[index] as Number).toByte() }
}

internal fun jsWriteString(filename: String, string: String) {
    fs.writeFileSync(filename, string, "utf8")
}

internal fun jsWriteBytes(filename: String, bytes: ByteArray) {
    fs.writeFileSync(filename, bytes)
}

internal fun jsMkdir(pathName: String): Boolean {
    fs.mkdirSync(pathName, js("({ recursive: true })"))
    return true
}

internal fun jsRm(pathName: String): Boolean {
    try {
        fs.rmSync(pathName, js("({ recursive: true, force: true })"))
        return true
    } catch (_: dynamic) {
        return try {
            fs.unlinkSync(pathName)
            true
        } catch (_: dynamic) {
            false
        }
    }
}

internal fun jsMktemp(): String {
    val tempName = "tmp-${Date.now().toLong()}-${Random.nextInt(1_000_000)}.tmp"
    val fileName = path.join(os.tmpdir(), tempName) as String
    fs.writeFileSync(fileName, "")
    return fileName
}

/** Open a file and return a numeric file descriptor. */
internal fun jsOpen(filename: String, readOnly: Boolean): Int {
    val fd: dynamic = fs.openSync(resolveTestPath(filename), if (readOnly) "r" else "r+")
    return fd as Int
}

/** Read exactly like POSIX pread: fileOffset is independent of the fd's internal position. */
internal fun jsPread(fd: Int, buf: ByteArray, offset: Int, length: Int, fileOffset: Long): Int {
    val nodeBuf: dynamic = Buffer.alloc(length)
    val bytesRead = (fs.readSync(fd, nodeBuf, 0, length, fileOffset.toInt()) as Number).toInt()
    for (i in 0 until bytesRead) {
        buf[offset + i] = (nodeBuf[i] as Number).toByte()
    }
    return bytesRead
}

/** Write exactly like POSIX pwrite: fileOffset is independent of the fd's internal position. */
internal fun jsPwrite(fd: Int, buf: ByteArray, offset: Int, length: Int, fileOffset: Long): Int {
    val nodeBuf: dynamic = Buffer.from(buf.copyOfRange(offset, offset + length))
    val written = fs.writeSync(fd, nodeBuf, 0, length, fileOffset.toInt()) as Int
    fs.fsyncSync(fd)
    return written
}

/** Close a file descriptor. */
internal fun jsClose(fd: Int) {
    fs.closeSync(fd)
}