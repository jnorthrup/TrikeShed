package borg.trikeshed.userspace.nio.spi

import borg.trikeshed.PosixUringIO
import borg.trikeshed.userspace.nio.channels.spi.LinuxChannelOperations
import borg.trikeshed.userspace.nio.channels.spi.PosixProcessOperations
import borg.trikeshed.userspace.nio.channels.spi.PosixReactorOperations
import borg.trikeshed.userspace.nio.file.spi.LinuxFileOperations
import borg.trikeshed.userspace.nio.file.spi.LinuxSystemOperations
import kotlin.coroutines.CoroutineContext

actual fun platformNioProviders(): List<CoroutineContext.Element> {
    val uringAvailable = PosixUringIO.isAvailable(entries = 2)
    val kernelHint = runCatching {
        val modules = listOf("/proc/modules", "/proc/sys/kernel/io_uring_disabled").mapNotNull { path ->
            runCatching { java.io.File(path).readText().trim().take(80) }.getOrNull()
        }
        modules.firstOrNull { it.contains("io_uring", ignoreCase = true) } ?: ""
    }.getOrDefault("")

    val report = NioCapabilityReport(
        backendName = if (uringAvailable) "io_uring" else "posix_aio",
        ioUringAvailable = uringAvailable,
        capabilities = if (uringAvailable) listOf("read", "write", "fsync", "poll", "net") else listOf("read", "write", "fsync"),
        kernelHint = kernelHint,
        checkedAt = kotlinx.datetime.Clock.System.now().toEpochMilliseconds(),
    )

    return listOf(
        report,
        LinuxFileOperations(),
        LinuxSystemOperations(),
        LinuxChannelOperations(),
        PosixReactorOperations(),
        PosixProcessOperations(),
    )
}
