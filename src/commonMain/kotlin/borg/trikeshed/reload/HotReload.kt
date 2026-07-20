package borg.trikeshed.reload

import borg.trikeshed.userspace.nio.file.spi.FileOperations
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.coroutineScope

class HotReloader(
    private val fileOps: FileOperations,
    private val filePath: String,
    private val onReload: () -> Unit
) {
    companion object {
        const val POLL_INTERVAL_MS = 1000L
    }

    private var lastHash: Int = 0

    suspend fun start() = coroutineScope {
        if (fileOps.exists(filePath)) {
            lastHash = fileOps.readAllBytes(filePath).contentHashCode()
        }
        
        while (isActive) {
            delay(POLL_INTERVAL_MS)
            if (fileOps.exists(filePath)) {
                val currentHash = fileOps.readAllBytes(filePath).contentHashCode()
                if (currentHash != lastHash) {
                    lastHash = currentHash
                    onReload()
                }
            } else if (lastHash != 0) {
                // File was deleted
                lastHash = 0
                onReload()
            }
        }
    }
}
