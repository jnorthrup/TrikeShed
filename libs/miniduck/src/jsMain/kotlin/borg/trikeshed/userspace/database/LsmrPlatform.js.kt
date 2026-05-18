package borg.trikeshed.userspace.database

actual suspend fun persistSegmentToDisk(
    rootPath: String,
    fileName: String,
    entries: Map<String, ByteArray>
): Unit = throw NotImplementedError("persistSegmentToDisk not implemented on JS")

actual suspend fun loadKeyFromSegment(
    rootPath: String,
    fileName: String,
    key: String
): ByteArray? {
    throw NotImplementedError("loadKeyFromSegment not implemented on JS")
}

actual fun deleteSegmentFile(rootPath: String, fileName: String): Unit = throw NotImplementedError("deleteSegmentFile not implemented on JS")
