package borg.trikeshed.platform.nio

/**
 * POSIX-specific backend detection
 * Uses kqueue on macOS/BSD
 */
actual fun detectBackend(config: BackendConfig): Result<PlatformBackend> {
    return runCatching {
        KqueuePlatformBackend(config)
    }
}
