package borg.trikeshed.userspace.database

import borg.trikeshed.Files


actual suspend fun persistSegmentToDisk(rootPath: String, fileName: String, entries: Map<String, ByteArray>) {
    val path = segmentPath(rootPath, fileName)
    val bytes = encodeSegment(entries)
    Files.write(path, bytes)
}

actual suspend fun loadKeyFromSegment(rootPath: String, fileName: String, key: String): ByteArray? {
    val path = segmentPath(rootPath, fileName)
    if (!Files.exists(path)) return null
    val segment = Files.readAllBytes(path)
    return decodeSegment(segment, key)
}

actual fun deleteSegmentFile(rootPath: String, fileName: String) {
    Files.deleteRecursively(segmentPath(rootPath, fileName))
}
fun segmentPath(rootPath: String, fileName: String): String =
    if (rootPath.isEmpty()) fileName
    else if (rootPath.endsWith('/')) "$rootPath$fileName"
    else "$rootPath/$fileName"
fun encodeSegment(entries: Map<String, ByteArray>): ByteArray {
    val totalSize = entries.entries.sumOf { (key, value) -> 4 + key.encodeToByteArray().size + 4 + value.size }
    val bytes = ByteArray(totalSize)
    var pos = 0
    for ((key, value) in entries) {
        val keyBytes = key.encodeToByteArray()
        pos = writeInt(bytes, pos, keyBytes.size)
        keyBytes.copyInto(bytes, pos)
        pos += keyBytes.size
        pos = writeInt(bytes, pos, value.size)
        value.copyInto(bytes, pos)
        pos += value.size
    }
    return bytes
}
fun decodeSegment(bytes: ByteArray, key: String): ByteArray? {
    var pos = 0
    while (pos + 8 <= bytes.size) {
        val keyLength = readInt(bytes, pos)
        pos += 4
        if (pos + keyLength > bytes.size) return null
        val currentKey = bytes.copyOfRange(pos, pos + keyLength).decodeToString()
        pos += keyLength
        if (pos + 4 > bytes.size) return null
        val valueLength = readInt(bytes, pos)
        pos += 4
        if (pos + valueLength > bytes.size) return null
        if (currentKey == key) {
            return bytes.copyOfRange(pos, pos + valueLength)
        }
        pos += valueLength
    }
    return null
}
fun writeInt(bytes: ByteArray, pos: Int, value: Int): Int {
    bytes[pos] = ((value ushr 24) and 0xFF).toByte()
    bytes[pos + 1] = ((value ushr 16) and 0xFF).toByte()
    bytes[pos + 2] = ((value ushr 8) and 0xFF).toByte()
    bytes[pos + 3] = (value and 0xFF).toByte()
    return pos + 4
}
fun readInt(bytes: ByteArray, pos: Int): Int =
    ((bytes[pos].toInt() and 0xFF) shl 24) or
        ((bytes[pos + 1].toInt() and 0xFF) shl 16) or
        ((bytes[pos + 2].toInt() and 0xFF) shl 8) or
        (bytes[pos + 3].toInt() and 0xFF)
