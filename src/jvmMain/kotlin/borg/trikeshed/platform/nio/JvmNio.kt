package borg.trikeshed.platform.nio

/**
 * JVM-specific backend detection
 * JVM uses Java NIO selectors; actual implementation deferred
 */
actual fun detectBackend(config: BackendConfig): Result<PlatformBackend> {
    return Result.failure(NotImplementedError("JVM NIO backend requires Java NIO selector implementation"))
}
