package borg.trikeshed.userspace.database

actual suspend fun persistSegmentToDisk(
    rootPath: CharSequence,
    fileName: CharSequence,
    entries: Map<CharSequence, ByteArray>
) {
}

actual suspend fun loadKeyFromSegment(
    rootPath: CharSequence,
    fileName: CharSequence,
    key: CharSequence
): ByteArray? {
    throw NotImplementedError("loadKeyFromSegment not implemented on POSIX")
}

actual fun deleteSegmentFile(rootPath: CharSequence, fileName: CharSequence) {
}
