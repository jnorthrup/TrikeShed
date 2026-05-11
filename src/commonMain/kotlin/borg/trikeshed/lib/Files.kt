package borg.trikeshed.lib

import borg.trikeshed.userspace.nio.file.spi.FileOperations

/**
 * Platform filesystem accessor.
 *
 * Resolves to the platform [FileOperations] registered by [PlatformProviders].
 * Usage unchanged: `Files.readString("foo.txt")`, `Files.cwd()`, etc.
 *
 * The old expect/actual object is gone — this is a thin property delegating
 * to the NIO SPI [FileOperations] interface. No more dual-layer indirection.
 */
val Files: FileOperations by lazy {
    // Try to find a registered FileOperations. If none is registered yet
    // (e.g. during static init), platforms register theirs eagerly.
    // Fallback: each platform module provides an expect/actual for defaultFileOperations.
    defaultFileOperations()
}

/** Platform hook — each target provides the default FileOperations instance. */
internal expect fun defaultFileOperations(): FileOperations
