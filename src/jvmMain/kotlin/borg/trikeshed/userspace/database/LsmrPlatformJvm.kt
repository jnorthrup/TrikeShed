package borg.trikeshed.userspace.database

import java.nio.file.Files
import java.nio.file.Paths
import java.nio.charset.StandardCharsets
import java.util.Base64

actual suspend fun persistSegmentToDisk(rootPath: String, fileName: String, entries: Map<String, ByteArray>) {
    val dir = Paths.get(rootPath)
    try { Files.createDirectories(dir) } catch (_: Throwable) {}
    val path = dir.resolve(fileName)
    val encoder = Base64.getEncoder()
    Files.newBufferedWriter(path, StandardCharsets.UTF_8).use { writer ->
        for ((k, v) in entries) {
            val encoded = encoder.encodeToString(v)
            writer.write(k + "\t" + encoded)
            writer.newLine()
        }
    }
}

actual suspend fun loadKeyFromSegment(rootPath: String, fileName: String, key: String): ByteArray? {
    val path = Paths.get(rootPath, fileName)
    if (!Files.exists(path)) return null
    val decoder = Base64.getDecoder()
    val lines = Files.readAllLines(path, StandardCharsets.UTF_8)
    for (line in lines) {
        val idx = line.indexOf('\t')
        if (idx < 0) continue
        val k = line.substring(0, idx)
        if (k == key) {
            return decoder.decode(line.substring(idx + 1))
        }
    }
    return null
}

actual fun deleteSegmentFile(rootPath: String, fileName: String) {
    try {
        val path = Paths.get(rootPath, fileName)
        Files.deleteIfExists(path)
    } catch (_: Throwable) {
        // ignore
    }
}
