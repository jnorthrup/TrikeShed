package borg.trikeshed.lib

import kotlin.js.Date
import kotlin.random.Random

val fs: dynamic = js("require('fs')")
val os: dynamic = js("require('os')")
val path: dynamic = js("require('path')")
val processObj: dynamic = js("process")
val Buffer: dynamic = js("globalThis.Buffer")

internal fun jsCwd(): CharSequence = processObj.cwd() as CharSequence

internal fun jsHomeDir(): CharSequence =
    (processObj.env.HOME as? CharSequence)
        ?: (processObj.env.USERPROFILE as? CharSequence)
        ?: jsCwd()

internal fun jsExists(filename: CharSequence): Boolean = fs.existsSync(filename) as Boolean

internal fun jsReadString(filename: CharSequence): CharSequence = fs.readFileSync(filename, "utf8") as CharSequence

internal fun jsReadBytes(filename: CharSequence): ByteArray {
    val buffer: dynamic = fs.readFileSync(filename)
    val size = (buffer.length as Number).toInt()
    return ByteArray(size) { index -> (buffer[index] as Number).toByte() }
}

internal fun jsWriteString(filename: CharSequence, string: CharSequence) {
    fs.writeFileSync(filename, string, "utf8")
}

internal fun jsWriteBytes(filename: CharSequence, bytes: ByteArray) {
    fs.writeFileSync(filename, bytes)
}

internal fun jsMkdir(pathName: CharSequence): Boolean {
    fs.mkdirSync(pathName, js("({ recursive: true })"))
    return true
}

internal fun jsRm(pathName: CharSequence): Boolean {
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

internal fun jsMktemp(): CharSequence {
    val tempName = "tmp-${Date.now().toLong()}-${Random.nextInt(1_000_000)}.tmp"
    val fileName = path.join(os.tmpdir(), tempName) as CharSequence
    fs.writeFileSync(fileName, "")
    return fileName
}

/** Open a file and return a numeric file descriptor. */
internal fun jsOpen(filename: CharSequence, readOnly: Boolean): Int {
    val fd: dynamic = fs.openSync(filename, if (readOnly) "r" else "r+")
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
