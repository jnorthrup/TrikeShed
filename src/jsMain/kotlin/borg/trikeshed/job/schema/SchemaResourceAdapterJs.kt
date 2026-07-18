package borg.trikeshed.job.schema

private external fun require(module: String): dynamic

actual fun loadConfixSchemaBytes(path: String): ByteArray {
    val fs = require("fs")
    val realPath = path.replace("classpath:/", "src/commonMain/resources/")
    val buffer = fs.readFileSync(realPath)
    val length = buffer.length as Int
    val array = ByteArray(length)
    for (i in 0 until length) {
        array[i] = buffer[i] as Byte
    }
    return array
}
