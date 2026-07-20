package borg.trikeshed.browser.storage

data class BrowserVolumeConfig(
    val blockSize: Int = 4096,
    val capacityBytes: Long = blockSize.toLong() * 1024L,
    val namespace: String = "trikeshed",
    val flushDebounceMs: Long = 50,
) {
    init {
        require(capacityBytes % blockSize == 0L) {
            "capacityBytes ($capacityBytes) must be a multiple of blockSize ($blockSize)"
        }
        require(!namespace.contains(":")) {
            "namespace cannot contain ':' (used as a key delimiter)"
        }
    }
}
