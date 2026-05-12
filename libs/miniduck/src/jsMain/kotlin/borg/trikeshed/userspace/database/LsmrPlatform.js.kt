package borg.trikeshed.userspace.database

actual suspend fun persistSegmentToDisk(
    rootPath: CharSequence,
    fileName: CharSequence,
    entries: Map<CharSequence, ByteArray>
): Unit = throw NotImplementedError("persistSegmentToDisk not implemented on JS")

actual suspend fun loadKeyFromSegment(
    rootPath: CharSequence,
    fileName: CharSequence,
    key: CharSequence
): ByteArray? {
    throw NotImplementedError("loadKeyFromSegment not implemented on JS")
}

actual fun deleteSegmentFile(rootPath: CharSequence, fileName: CharSequence): Unit = throw NotImplementedError("deleteSegmentFile not implemented on JS")
