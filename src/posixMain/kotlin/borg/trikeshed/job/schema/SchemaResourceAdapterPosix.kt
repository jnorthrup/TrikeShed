package borg.trikeshed.job.schema

import kotlinx.cinterop.*
import platform.posix.*

actual fun loadConfixSchemaBytes(path: String): ByteArray {
    val resourcePath = path.removePrefix("classpath:")
    val file = fopen("src/commonMain/resources$resourcePath", "rb")
        ?: throw IllegalStateException("Schema resource not found: $path")
    fseek(file, 0, SEEK_END)
    val size = ftell(file).toInt()
    fseek(file, 0, SEEK_SET)
    val bytes = ByteArray(size)
    bytes.usePinned { pinned ->
        fread(pinned.addressOf(0), size.toULong(), 1u, file)
    }
    fclose(file)
    return bytes
}
