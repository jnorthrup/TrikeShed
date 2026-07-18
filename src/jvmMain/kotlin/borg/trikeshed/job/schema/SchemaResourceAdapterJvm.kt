package borg.trikeshed.job.schema

import java.io.File

actual fun loadConfixSchemaBytes(path: String): ByteArray {
    val resourcePath = path.removePrefix("classpath:")
    val url = object {}.javaClass.getResource(resourcePath)
    if (url != null) return url.readBytes()

    val relativePath = "src/commonMain/resources$resourcePath"
    return File(relativePath).readBytes()
}
