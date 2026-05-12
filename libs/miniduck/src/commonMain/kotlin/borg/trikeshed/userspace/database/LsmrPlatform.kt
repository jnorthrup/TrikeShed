package borg.trikeshed.userspace.database

expect suspend fun persistSegmentToDisk(rootPath: CharSequence, fileName: CharSequence, entries: Map<CharSequence, ByteArray>)
expect suspend fun loadKeyFromSegment(rootPath: CharSequence, fileName: CharSequence, key: CharSequence): ByteArray?
expect fun deleteSegmentFile(rootPath: CharSequence, fileName: CharSequence)
