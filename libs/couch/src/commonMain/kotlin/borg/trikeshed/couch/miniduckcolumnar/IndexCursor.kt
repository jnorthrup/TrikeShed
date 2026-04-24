package borg.trikeshed.couch.miniduckcolumnar

interface IndexCursor {
    /**
     * Pre-scan the index file to build in-memory lookup structure.
     * This should read the index header and build block offset map.
     */
    fun preScan(indexPath: String)
    
    /**
     * Seek to the first entry with blockOffset >= target.
     * This should adjust mmap/cache blocks lazily as needed.
     */
    fun seek(target: Long): Boolean
    
    /**
     * Move to next entry in the index.
     * Should handle lazy mmap/cache block adjustments.
     */
    fun next(): Boolean
    
    /**
     * Get the current entry's block offset.
     */
    fun current(): Long
    
    /**
     * Close and release any mmap resources.
     */
    fun close()
}
