package borg.trikeshed.platform.nio

/**
 * Linux-specific backend detection
 * Tries io_uring first, falls back to epoll
 */
actual fun detectBackend(config: BackendConfig): Result<PlatformBackend> {
    return runCatching {
        UringPlatformBackend(config)
    }.recoverCatching {
        EpollPlatformBackend(config)
    }
}
