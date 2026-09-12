package borg.trikeshed.userspace

import borg.trikeshed.platform.loadPlatformHost
import java.nio.file.Files
import java.nio.file.Path

/**
 * Compile-time native-depth gate for the JVM actual.
 *
 * 0 = emulation only (POSIX-shaped JDK primitives servicing SQEs)
 * 1 = JNI bridge to the kernel ring (probe still validates ELF/ABI/NOP)
 *
 * The facade surface is byte-identical above either level; going more native
 * means raising this const, never adding a second API. The runtime probe
 * cannot exceed the level pinned here — the const is the ceiling.
 */
internal const val JVM_NATIVE_URING_LEVEL: Int = 1

/** Discovery of the repo's JNI execution module. No process or IO CLI bridge. */
fun discoverJvmUringBackend(entries: Int): UringBackendDiscovery {
    require(entries > 0)
    val host = loadPlatformHost().descriptor
    if (JVM_NATIVE_URING_LEVEL < 1) {
        return UringBackendDiscovery(UringProbeReport(host, UringProbeState.MODULE_ABSENT,
            UringProbePhase.DISCOVERY, null,
            "JVM_NATIVE_URING_LEVEL=$JVM_NATIVE_URING_LEVEL pins the actual to emulation"))
    }
    fun unavailable(state: UringProbeState, detail: String, module: String? = null,
                    phase: UringProbePhase = UringProbePhase.DISCOVERY, errno: Int? = null) =
        UringBackendDiscovery(UringProbeReport(host, state, phase, module, detail, errno))
    uringHostConstraint(host)?.let { return unavailable(it, "JNI io_uring requires a known Linux arm64 or x86_64 host") }
    var module: String? = null
    var phase = UringProbePhase.DISCOVERY
    try {
        val configured = System.getProperty("trikeshed.uring.library")?.takeIf { it.isNotBlank() }
        val candidate = if (configured != null) Path.of(configured).toAbsolutePath() else {
            val filename = System.mapLibraryName("trikeshed_uring")
            System.getProperty("java.library.path", "").split(java.io.File.pathSeparatorChar)
                .asSequence().filter { it.isNotBlank() }.map { Path.of(it, filename).toAbsolutePath() }
                .firstOrNull { Files.isRegularFile(it) }
        }
        module = candidate?.toString()
        if (candidate == null || !Files.exists(candidate))
            return unavailable(UringProbeState.MODULE_ABSENT, "No trikeshed_uring JNI module found", module)
        val header = Files.newInputStream(candidate).use { it.readNBytes(20) }
        uringElfCompatibility(header, host.architecture)?.let {
            return unavailable(it, "JNI module ELF class, byte order or machine does not match ${host.architecture}", module)
        }
        phase = UringProbePhase.LOAD
        System.load(candidate.toString())
        if (JvmUring.abiVersion() != 3)
            return unavailable(UringProbeState.ABI_MISMATCH, "JNI bridge protocol must be version 3 (descriptor STATX)", module, phase)
        phase = UringProbePhase.SETUP
        val handle = JvmUring.open(entries)
        if (handle <= 0L) {
            val errno = if (handle in -4095L..-1L) -handle.toInt() else null
            return unavailable(errno?.let(::uringErrnoState) ?: UringProbeState.PROBE_FAILED,
                "io_uring setup/operation probe returned $handle", module, phase, errno)
        }
        phase = UringProbePhase.OPERATIONS
        val backend = try { jvmNativeChannelBackend(handle) } catch (failure: Throwable) {
            try { JvmUring.close(handle) } catch (cleanup: Throwable) {
                return unavailable(UringProbeState.PROBE_FAILED,
                    "${failure.message}; module cleanup failed: ${cleanup.message}", module, phase)
            }
            throw failure
        }
        return probeUringBackend(host, candidate.toString(), backend)
    } catch (failure: SecurityException) {
        return unavailable(UringProbeState.RUNTIME_RESTRICTED, failure.message ?: "Runtime denied module access", module, phase)
    } catch (failure: UnsatisfiedLinkError) {
        return unavailable(UringProbeState.ABI_MISMATCH, failure.message ?: "JNI module or symbol is incompatible", module, phase)
    } catch (failure: LinkageError) {
        return unavailable(UringProbeState.ABI_MISMATCH, failure.message ?: "JNI linkage failed", module, phase)
    } catch (failure: Exception) {
        return unavailable(if (phase == UringProbePhase.DISCOVERY || phase == UringProbePhase.LOAD)
            UringProbeState.MODULE_LOAD_FAILED else UringProbeState.PROBE_FAILED,
            failure.message ?: failure.toString(), module, phase)
    }
}
