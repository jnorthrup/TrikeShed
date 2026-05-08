package borg.trikeshed.userspace.database

actual suspend fun persistSegmentToDisk(
    rootPath: String,
    fileName: String,
    entries: Map<String, ByteArray>
) {
}

actual suspend fun loadKeyFromSegment(
    rootPath: String,
    fileName: String,
    key: String
): ByteArray? {
    throw NotImplementedError("loadKeyFromSegment not implemented on POSIX")
}

actual fun deleteSegmentFile(rootPath: String, fileName: String) {
}
