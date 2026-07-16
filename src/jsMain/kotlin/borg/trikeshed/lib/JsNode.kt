package borg.trikeshed.lib

import kotlin.js.Date
import kotlin.random.Random

// Node.js module access that is invisible to webpack's static analysis.
// eval("require(...)") prevents webpack from generating a static require stub,
// which would crash the browser bundle with "Cannot find module 'fs'".
// Browser code paths never call these — only Node paths do.

private fun nodeRequire(name: String): dynamic = js("eval(\"require\")(name)")

val fs: dynamic get() = nodeRequire("fs")
val os: dynamic get() = nodeRequire("os")
val path: dynamic get() = nodeRequire("path")
val processObj: dynamic get() = js("eval(\"process\")")
val Buffer: dynamic get() = js("eval(\"globalThis.Buffer\")")

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

internal fun jsOpen(filename: String, readOnly: Boolean): Int {
    val fd: dynamic = fs.openSync(resolveTestPath(filename), if (readOnly) "r" else "r+")
    return fd as Int
}

internal fun jsPread(fd: Int, buf: ByteArray, offset: Int, length: Int, fileOffset: Long): Int {
    val nodeBuf: dynamic = Buffer.alloc(length)
    val bytesRead = (fs.readSync(fd, nodeBuf, 0, length, fileOffset.toInt()) as Number).toInt()
    for (i in 0 until bytesRead) {
        buf[offset + i] = (nodeBuf[i] as Number).toByte()
    }
    return bytesRead
}

internal fun jsPwrite(fd: Int, buf: ByteArray, offset: Int, length: Int, fileOffset: Long): Int {
    val nodeBuf: dynamic = Buffer.from(buf.copyOfRange(offset, offset + length))
    val written = fs.writeSync(fd, nodeBuf, 0, length, fileOffset.toInt()) as Int
    fs.fsyncSync(fd)
    return written
}

internal fun jsClose(fd: Int) {
    fs.closeSync(fd)
}