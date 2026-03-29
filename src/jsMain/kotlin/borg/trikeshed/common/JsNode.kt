package borg.trikeshed.common

import kotlin.js.Date
import kotlin.random.Random

private val fs: dynamic = js("require('fs')")
private val os: dynamic = js("require('os')")
private val path: dynamic = js("require('path')")
private val processObj: dynamic = js("process")

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
