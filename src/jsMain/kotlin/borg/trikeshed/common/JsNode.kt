package borg.trikeshed.common

import kotlin.js.Date
import kotlin.random.Random
val fs: dynamic = js("require('fs')")val os: dynamic = js("require('os')")val path: dynamic = js("require('path')")val processObj: dynamic = js("process")val Buffer: dynamic = js("globalThis.Buffer")

internal fun jsCwd(): String = processObj.cwd() as String

internal fun jsHomeDir(): String =
    (processObj.env.HOME as? String)
        ?: (processObj.env.USERPROFILE as? String)
        ?: jsCwd()

internal fun jsExists(filename: String): Boolean = fs.existsSync(filename) as Boolean

internal fun jsReadString(filename: String): String = fs.readFileSync(filename, "utf8") as String

internal fun jsReadBytes(filename: String): ByteArray {
    val buffer: dynamic = fs.readFileSync(filename)
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
    val fd: dynamic = fs.openSync(filename, if (readOnly) "r" else "w+")
    return fd as Int
}

/** Read exactly like POSIX pread: fileOffset is independent of the fd's internal position. */
internal fun jsPread(fd: Int, buf: ByteArray, offset: Int, length: Int, fileOffset: Long): Int {
    val nodeBuf: dynamic = fs.readFileSync(fd, js("{encoding: null, flag: 'r'}"))
    val size = (nodeBuf.length as Number).toInt()
    val start = fileOffset.toInt().coerceAtLeast(0)
    if (start >= size) return 0
    val count = minOf(length, size - start)
    for (i in 0 until count) {
        buf[offset + i] = (nodeBuf[start + i] as Number).toByte()
    }
    return count
}

/** Write exactly like POSIX pwrite: fileOffset is independent of the fd's internal position. */
internal fun jsPwrite(fd: Int, buf: ByteArray, offset: Int, length: Int, fileOffset: Long): Int {
    val nodeBuf: dynamic = Buffer.from(buf.copyOfRange(offset, offset + length))
    return fs.writeSync(fd, nodeBuf, 0, length, fileOffset) as Int
}

/** Close a file descriptor. */
internal fun jsClose(fd: Int) {
    fs.closeSync(fd)
}
