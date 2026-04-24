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
    TODO("Not yet implemented")
}

actual fun deleteSegmentFile(rootPath: String, fileName: String) {
}
