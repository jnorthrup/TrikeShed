package borg.trikeshed.userspace.database

actual suspend fun persistSegmentToDisk(rootPath: CharSequence, fileName: CharSequence, entries: Map<CharSequence, ByteArray>) {}
actual suspend fun loadKeyFromSegment(rootPath: CharSequence, fileName: CharSequence, key: CharSequence): ByteArray? = null
actual fun deleteSegmentFile(rootPath: CharSequence, fileName: CharSequence) {}
