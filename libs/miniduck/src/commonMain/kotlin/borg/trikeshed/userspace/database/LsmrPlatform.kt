package borg.trikeshed.userspace.database

expect suspend fun persistSegmentToDisk(rootPath: String, fileName: String, entries: Map<String, ByteArray>)
expect suspend fun loadKeyFromSegment(rootPath: String, fileName: String, key: String): ByteArray?
expect fun deleteSegmentFile(rootPath: String, fileName: String)
